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
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MangaLibApi(private val client: OkHttpClient) {

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
        val token = readToken() ?: run {
            logcat(LogPriority.INFO) { "MangaLib tracker: нет токена (залогинься через WebView расширения)" }
            return@withIOContext
        }
        val h = headers(token)
        val id = mangaId(slug)
        val chapters = with(json) {
            client.newCall(GET("$API_DOMAIN/api/manga/$slug/chapters", h))
                .awaitSuccess().parseAs<Data<List<ChapterDto>>>()
        }.data
        val target = chapters.filter {
            val n = it.number.toDoubleOrNull() ?: return@filter false
            abs(n - chapterNumber) < EPS
        }
        if (target.isEmpty()) {
            logcat(LogPriority.WARN) { "MangaLib tracker: глава $chapterNumber не найдена в $slug" }
            return@withIOContext
        }
        // ponytail: при нескольких бранчах с одним номером помечаем все — соответствует «прочитал N на сайте»
        for (ch in target) {
            client.newCall(POST("$API_DOMAIN/api/manga/$id/chapters/${ch.id}/view", h)).awaitSuccess()
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
        private const val EPS = 1e-4
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    }
}
