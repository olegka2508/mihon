package eu.kanade.tachiyomi.data.track.mangalib

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Data<T>(val data: T)

/** JWT из localStorage['auth'] mangalib. Формат совпадает с расширением libgroup. */
@Serializable
class AuthToken(
    private val auth: Auth? = null,
    private val token: Token? = null,
) {
    @Serializable
    class Auth(val id: Int)

    @Serializable
    class Token(
        val timestamp: Long = 0,
        @SerialName("expires_in") val expiresIn: Long = 0,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("access_token") val accessToken: String = "",
    )

    fun isValid(): Boolean = auth != null && token != null && token.accessToken.isNotBlank()

    fun isExpired(): Boolean {
        val expiresAt = (token?.timestamp ?: 0) + (token?.expiresIn ?: 0) * 1000
        return expiresAt < System.currentTimeMillis()
    }

    /** Значение заголовка Authorization: "Bearer eyJ…" */
    fun header(): String = "${token!!.tokenType} ${token.accessToken}"
}

@Serializable
class ChapterDto(
    val id: Long,
    val number: String,
    val volume: String,
)

@Serializable
class BookmarkDto(
    val item: BookmarkItem? = null,
) {
    @Serializable
    class BookmarkItem(
        val number: String? = null,
        val volume: String? = null,
    )
}
