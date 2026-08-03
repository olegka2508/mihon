package eu.kanade.tachiyomi.data.track.remanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.net.URLDecoder
import kotlin.math.ceil

class RemangaApi(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    /**
     * Токен берём из общего cookie-jar — тем же способом, что расширение Remanga.
     * Куки кладёт WebView-логин расширения, второй логин пользователю не нужен.
     */
    private fun readToken(): String? = client.cookieJar
        .loadForRequest(SITE_URL.toHttpUrl())
        .firstOrNull { it.name == "token" }
        ?.let { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

    private fun headers(token: String): Headers = Headers.Builder()
        .add("Accept", "application/json")
        .add("Referer", "$SITE_URL/")
        .add("User-Agent", USER_AGENT)
        .add("Authorization", "bearer $token")
        .build()

    private suspend fun title(dir: String, h: Headers): TitleDto = with(json) {
        client.newCall(GET("$API_URL/api/titles/$dir/", h))
            .awaitSuccess().parseAs<Content<TitleDto>>()
    }.content

    /**
     * PUSH: пометить прочитанными все главы вплоть до [chapterNumber].
     * Отмечаем диапазон (current_reading, chapterNumber] — так закрываем «пробел»:
     * при прыжке вперёд промежуточные главы тоже становятся прочитанными (как на mangalib,
     * где сайт сам заполняет между; у Remanga такого нет — заполняем клиентом).
     * POST /api/activity/views/ тело {"chapter_ids":[…]} — батч кнопки «прочитано»
     * (обратимо через DELETE тем же телом). Сервер режет большой payload → шлём чанками.
     */
    suspend fun markChapterRead(dir: String, chapterNumber: Double): Unit = withIOContext {
        // Ошибки бросаем: TrackChapter поставит главу в DelayedTrackingStore на ретрай.
        val token = readToken()
            ?: throw IllegalStateException("Remanga: не залогинен — войди через WebView расширения")
        val h = headers(token)

        // 404 = dir устарел (тайтл переехал/снят) — штатный пропуск, а не повод для ретраев
        val title = try {
            title(dir, h)
        } catch (e: HttpException) {
            if (e.code != 404) throw e
            logcat(LogPriority.INFO) { "Remanga tracker: тайтл $dir не найден на сайте, push пропущен" }
            return@withIOContext
        }
        val branchId = title.branches.maxByOrNull { it.countChapters }?.id ?: run {
            logcat(LogPriority.INFO) { "Remanga tracker: у $dir нет веток, push пропущен" }
            return@withIOContext
        }

        // Нижняя граница — прогресс на сайте: ниже него всё уже прочитано, не трогаем.
        val from = title.currentReading?.chapter?.toDoubleOrNull() ?: 0.0
        val ids = collectChapterIds(branchId, from, chapterNumber, h)
        if (ids.isEmpty()) return@withIOContext // сайт уже впереди или главы не найдены

        ids.chunked(BATCH_SIZE).forEach { chunk ->
            val payload = buildJsonObject {
                putJsonArray("chapter_ids") { chunk.forEach { add(it) } }
            }
            client.newCall(
                POST("$API_URL/api/activity/views/", h, payload.toString().toRequestBody(JSON_MIME)),
            ).awaitSuccess()
        }
    }

    /**
     * PULL: последний прочитанный номер главы с сайта (merge-by-max в Mihon).
     * Берём content.current_reading — готовый указатель, один запрос без пагинации.
     * continue_reading СОЗНАТЕЛЬНО не используем: это следующая глава к прочтению,
     * она завысила бы прогресс на единицу и Mihon пометил бы непрочитанное прочитанным.
     */
    suspend fun fetchLastReadNumber(dir: String): Double? = withIOContext {
        // pull не должен бросать: зовётся перед каждым push и на открытии тайтла
        runCatching {
            val token = readToken() ?: return@runCatching null
            title(dir, headers(token)).currentReading?.chapter?.toDoubleOrNull()
        }.getOrNull()
    }

    /**
     * Собирает id глав с номером в (from, to]. Сервер режет count до 100 и игнорирует
     * фильтры, номера идут по возрастанию вместе с index (ordering=index).
     * Эвристика «номер≈index» даёт старт-страницу, но она может ПРОМАХНУТЬСЯ, если номера
     * опережают index (нумерация с большого числа) или from за концом ветки → сначала
     * откатываемся назад до страницы, чей минимум ≤ from, потом идём вперёд до to.
     * Обычная дочитка (from≈to) — 1–2 страницы; разовая выгрузка с нуля — вся ветка.
     */
    private suspend fun collectChapterIds(branchId: Long, from: Double, to: Double, h: Headers): List<Long> {
        if (to <= from) return emptyList()
        var page = maxOf(1, ceil(from / PAGE_SIZE).toInt())

        // Откат назад: пустая страница (за концом ветки) или минимум страницы выше from —
        // значит старт слишком высоко и мы бы перескочили нужные главы.
        var back = 0
        while (page > 1 && back < MAX_PAGES) {
            back++
            val min = chaptersPage(branchId, page, h).mapNotNull { it.chapter.toDoubleOrNull() }.minOrNull()
            if (min != null && min <= from + EPS) break
            page--
        }

        val ids = mutableListOf<Long>()
        repeat(MAX_PAGES) {
            val chapters = chaptersPage(branchId, page, h)
            if (chapters.isEmpty()) return ids // дошли до конца ветки
            val numbers = chapters.mapNotNull { it.chapter.toDoubleOrNull() }
            chapters.forEach { c ->
                val n = c.chapter.toDoubleOrNull() ?: return@forEach
                if (n > from + EPS && n <= to + EPS) ids.add(c.id)
            }
            // если минимум страницы уже выше to — дальше только более старшие главы
            if ((numbers.minOrNull() ?: Double.MAX_VALUE) > to + EPS) return ids
            page++
        }
        return ids
    }

    private suspend fun chaptersPage(branchId: Long, page: Int, h: Headers): List<ChapterDto> = with(json) {
        client.newCall(
            GET(
                "$API_URL/api/titles/chapters/?branch_id=$branchId&count=$PAGE_SIZE&ordering=index&page=$page",
                h,
            ),
        ).awaitSuccess().parseAs<Content<List<ChapterDto>>>()
    }.content

    companion object {
        private const val SITE_URL = "https://remanga.org"
        private const val API_URL = "https://api.remanga.org"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 60 // хватает на самую длинную ветку (~3865 глав)
        private const val BATCH_SIZE = 100 // чанк chapter_ids в одном POST
        private const val EPS = 1e-4
        private val JSON_MIME = "application/json".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/138.0.0.0 Mobile Safari/537.36"
    }
}
