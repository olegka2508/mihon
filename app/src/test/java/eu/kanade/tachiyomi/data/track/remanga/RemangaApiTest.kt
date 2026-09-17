package eu.kanade.tachiyomi.data.track.remanga

import okhttp3.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RemangaApiTest {

    @Test
    fun `prefers current auth token cookie and supports legacy name`() {
        val bothNames = listOf(cookie("token", "legacy"), cookie("auth:token", "current%20token"))

        assertEquals("current token", readRemangaToken(bothNames))
        assertEquals("legacy", readRemangaToken(listOf(cookie("token", "legacy"))))
        assertEquals("legacy", readRemangaToken(listOf(cookie("auth:token", ""), cookie("token", "legacy"))))
    }

    private fun cookie(name: String, value: String) = Cookie.Builder()
        .name(name)
        .value(value)
        .domain("remanga.org")
        .build()
}
