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
import kotlin.math.abs
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
     * PUSH: пометить главу [chapterNumber] прочитанной.
     * POST /api/activity/views/ тело {"chapter_ids":[id]} — то же, что шлёт кнопка
     * «отметить прочитанным» на сайте (обратимо через DELETE тем же телом).
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
        val chapter = findChapter(branchId, chapterNumber, h)
            ?: throw IllegalStateException("Remanga: глава $chapterNumber не найдена в $dir")

        val payload = buildJsonObject {
            putJsonArray("chapter_ids") { add(chapter.id) }
        }
        client.newCall(
            POST("$API_URL/api/activity/views/", h, payload.toString().toRequestBody(JSON_MIME)),
        ).awaitSuccess()
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
     * Ищет главу по номеру. Сервер режет count до 100 и игнорирует фильтры, зато номера
     * глав идут почти вровень с index — вычисляем нужную страницу и правим промах.
     * Обычно 1 запрос; у тайтла на 3865 глав перебор стоил бы 39.
     */
    private suspend fun findChapter(branchId: Long, target: Double, h: Headers): ChapterDto? {
        var page = maxOf(1, ceil(target / PAGE_SIZE).toInt())
        val seen = mutableSetOf<Int>()

        repeat(MAX_PROBES) {
            if (!seen.add(page)) return null
            val chapters = chaptersPage(branchId, page, h)
            if (chapters.isEmpty()) {
                // за последней страницей — шагаем назад
                page = (page - 1).takeIf { it >= 1 } ?: return null
                return@repeat
            }
            chapters.firstOrNull { abs((it.chapter.toDoubleOrNull() ?: return@firstOrNull false) - target) < EPS }
                ?.let { return it }

            val numbers = chapters.mapNotNull { it.chapter.toDoubleOrNull() }
            if (numbers.isEmpty()) return null
            val min = numbers.min()
            val max = numbers.max()
            page += when {
                target < min -> -maxOf(1, ceil((min - target) / PAGE_SIZE).toInt())
                target > max -> maxOf(1, ceil((target - max) / PAGE_SIZE).toInt())
                else -> return null // номер внутри диапазона страницы, но главы нет
            }
            if (page < 1) return null
        }
        return null
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
        private const val MAX_PROBES = 6
        private const val EPS = 1e-4
        private val JSON_MIME = "application/json".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/138.0.0.0 Mobile Safari/537.36"
    }
}
