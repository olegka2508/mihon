package eu.kanade.tachiyomi.data.track.remanga

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ответы Remanga завёрнуты в {"content": …} */
@Serializable
class Content<T>(val content: T)

@Serializable
class TitleDto(
    val branches: List<BranchDto> = emptyList(),
    // Прогресс чтения на сайте; без авторизации приходит null
    @SerialName("current_reading") val currentReading: ReadingDto? = null,
) {
    @Serializable
    class BranchDto(
        val id: Long,
        @SerialName("count_chapters") val countChapters: Int = 0,
    )

    @Serializable
    class ReadingDto(
        val chapter: String? = null,
    )
}

@Serializable
class ChapterDto(
    val id: Long,
    val chapter: String,
)
