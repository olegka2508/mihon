package eu.kanade.tachiyomi.data.track.mangalib

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

class MangaLibApi(baseClient: OkHttpClient) {

    // У mangalib жёсткий лимит ~1 rps: при массовой выгрузке без троттлинга сыпется HTTP 429.
    // rateLimit-перехватчик РАЗНОСИТ запросы во времени (блокирует, а не отбрасывает).
    private val client = baseClient.newBuilder()
        .rateLimit(permits = 1, period = 1.seconds)
        .build()

    private val json: Json by injectLazy()

    private fun headers(token: AuthToken): Headers = Headers.Builder()
        .add("Accept", "application/json")
        .add("Site-Id", SITE_ID)
        .add("Referer", "$BASE_URL/")
        .add("User-Agent", USER_AGENT)
        .add("Authorization", token.header())
        .build()

    /** slug вида "150895--foo" → manga_id "150895" */
    private fun mangaId(slug: String): String = slug.substringBefore("--")

    /**
     * PUSH: пометить главу с номером [chapterNumber] прочитанной на сайте.
     * POST /api/manga/{manga_id}/chapters/{chapter_id}/view (пустое тело).
     */
    suspend fun markChapterRead(slug: String, chapterNumber: Double): Unit = withIOContext {
        // Ошибки бросаем, а не глотаем: Mihon покажет сбой трекинга и поставит на ретрай
        // (DelayedTrackingUpdateJob). Тихий выход once уже стоил сессии диагностики.
        val token = readToken()
            ?: throw IllegalStateException("MangaLib: не залогинен — открой mangalib в браузере расширения")
        val h = headers(token)
        val id = mangaId(slug)
        val chapters = with(json) {
            client.newCall(GET("$API_DOMAIN/api/manga/$slug/chapters", h))
                .awaitSuccess().parseAs<Data<List<ChapterDto>>>()
        }.data
        // Тайтл снят с mangalib (API отдаёт пустой список) — штатная ситуация, отмечать нечего
        if (chapters.isEmpty()) {
            logcat(LogPriority.INFO) { "MangaLib tracker: у $slug нет глав в API, push пропущен" }
            return@withIOContext
        }
        val target = chapters.filter {
            val n = it.number.toDoubleOrNull() ?: return@filter false
            abs(n - chapterNumber) < EPS
        }
        if (target.isEmpty()) {
            throw IllegalStateException("MangaLib: глава $chapterNumber не найдена в $slug")
        }
        // ponytail: при нескольких бранчах с одним номером помечаем все — соответствует «прочитал N на сайте»
        for (ch in target) {
            client.newCall(POST("$API_DOMAIN/api/manga/$id/chapters/${ch.id}/view", h)).awaitSuccess()
        }
        movePointer(slug, target.first(), chapterNumber, h)
    }

    /**
     * Двигает указатель «продолжить чтение» на сайте (POST /api/bookmarks).
     * Главное — пометка read выше, поэтому сбой указателя логируем, но push не валим.
     */
    private suspend fun movePointer(slug: String, chapter: ChapterDto, chapterNumber: Double, h: Headers) {
        runCatching {
            val bookmark = with(json) {
                client.newCall(GET("$API_DOMAIN/api/manga/$slug/bookmark", h))
                    .awaitSuccess().parseAs<Data<BookmarkDto>>()
            }.data

            // Закладки нет → заводим её в «Читаю» (тайтл, начатый в Mihon, появляется на сайте).
            // Закладка есть → переиспортируем её status: своё значение перенесло бы тайтл
            // из «Любимые»/«Прочитано» в «Читаю». READING проверен живьём (POST /bookmarks → 201).
            val existing = bookmark.status
            val status = existing ?: READING
            val current = bookmark.item?.number?.toDoubleOrNull()
            // Уже в закладках и указатель не позади — двигать нечего
            if (existing != null && current != null && chapterNumber <= current) return@runCatching

            val payload = buildJsonObject {
                put("media_type", "manga")
                put("media_slug", slug)
                putJsonObject("bookmark") {
                    put("item_id", chapter.id)
                    put("status", status)
                }
                chapter.itemNumber?.let {
                    // без него meta остаётся от прежней главы и сайт покажет чужой номер
                    putJsonObject("meta") { put("item_number", it) }
                }
            }
            client.newCall(
                POST("$API_DOMAIN/api/bookmarks", h, payload.toString().toRequestBody(JSON_MIME)),
            ).awaitSuccess()
        }.onFailure {
            logcat(LogPriority.WARN, it) { "MangaLib tracker: указатель «продолжить» не сдвинут для $slug" }
        }
    }

    /**
     * PULL: последний прочитанный номер главы с сайта (для merge-by-max в Mihon).
     * GET /api/manga/{slug}/bookmark → item.number.
     */
    suspend fun fetchLastReadNumber(slug: String): Double? = withIOContext {
        // pull не должен бросать: вызывается перед каждым push (TrackChapter) и на открытии тайтла.
        // Любая ошибка/отсутствие токена → null (прогресс просто не подтянулся).
        runCatching {
            val token = readToken() ?: return@runCatching null
            val bookmark = with(json) {
                client.newCall(GET("$API_DOMAIN/api/manga/$slug/bookmark", headers(token)))
                    .awaitSuccess().parseAs<Data<BookmarkDto>>()
            }.data
            bookmark.item?.number?.toDoubleOrNull()
        }.getOrNull()
    }

    /**
     * Читает JWT из общего WebView localStorage['auth'] — тот же источник, что у расширения.
     * Пользователь логинится один раз через WebView расширения.
     */
    // Кэш токена: без него каждый push/pull поднимает WebView (при pull всей библиотеки — сотни раз)
    @Volatile
    private var cachedToken: AuthToken? = null

    private fun readToken(): AuthToken? {
        cachedToken?.takeIf { !it.isExpired() }?.let { return it }
        return readTokenFromWebView()?.also { cachedToken = it }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun readTokenFromWebView(): AuthToken? {
        val latch = CountDownLatch(1)
        var result: AuthToken? = null
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(Injekt.get<Application>())
            with(webView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val v = view ?: return
                    v.evaluateJavascript("javascript:localStorage['auth']") {
                        v.stopLoading()
                        v.destroy()
                        result = parseAuth(it)
                        latch.countDown()
                    }
                }
            }
            webView.loadUrl("$BASE_URL/")
        }
        latch.await(20, TimeUnit.SECONDS)
        return result?.takeIf { it.isValid() }
    }

    private fun parseAuth(raw: String?): AuthToken? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val str = if (raw.first() == '"' && raw.last() == '"') {
            raw.substringAfter('"').substringBeforeLast('"').replace("\\", "")
        } else {
            raw.replace("\\", "")
        }
        return runCatching { json.decodeFromString<AuthToken>(str) }.getOrNull()
    }

    companion object {
        private const val BASE_URL = "https://mangalib.me"
        private const val API_DOMAIN = "https://api.cdnlibs.org"
        private const val SITE_ID = "1"
        private const val READING = 1 // status папки «Читаю» для новых тайтлов
        private const val EPS = 1e-4
        private val JSON_MIME = "application/json".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    }
}
