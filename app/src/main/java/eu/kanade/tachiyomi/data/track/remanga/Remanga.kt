package eu.kanade.tachiyomi.data.track.remanga

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.Source
import tachiyomi.i18n.MR
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track as DomainTrack

class Remanga(id: Long) : BaseTracker(id, "Remanga"), EnhancedTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L

        // FQCN снят с живого APK расширения (Tachiyomi_RUS v1.4.116, classes3.dex):
        // обычный класс, без кодогена ExtensionGenerated в отличие от MangaLib.
        private val ACCEPTED_SOURCES = listOf(
            "eu.kanade.tachiyomi.extension.ru.remanga.Remanga",
        )
    }

    private val api by lazy { RemangaApi(client) }

    override fun getLogo() = R.drawable.brand_remanga

    override fun getStatusList(): List<Long> = listOf(READING, COMPLETED)

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = -1

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): List<String> = emptyList()

    override fun displayScore(track: DomainTrack): String = ""

    /** dir из tracking_url "/api/titles/{dir}/" */
    private fun dirOf(track: Track): String =
        track.tracking_url.substringAfter("/api/titles/").trim('/')

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (didReadChapter && track.last_chapter_read > 0) {
            api.markChapterRead(dirOf(track), track.last_chapter_read)
            if (track.status != COMPLETED) {
                track.status = if (track.total_chapters > 0 &&
                    track.last_chapter_read.toLong() == track.total_chapters
                ) {
                    COMPLETED
                } else {
                    READING
                }
            }
        }
        return track
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track = track

    override suspend fun search(query: String): List<TrackSearch> = emptyList()

    override suspend fun refresh(track: Track): Track {
        val remote = api.fetchLastReadNumber(dirOf(track)) ?: return track
        // никогда не понижаем локальный прогресс (merge-by-max)
        track.last_chapter_read = maxOf(track.last_chapter_read, remote)
        return track
    }

    override suspend fun login(username: String, password: String) = saveCredentials("user", "pass")

    // Активация трекера по факту сохранённых dummy-креды (как Komga).
    override fun loginNoop() {
        saveCredentials("user", "pass")
    }

    override fun getAcceptedSources(): List<String> = ACCEPTED_SOURCES

    // Лёгкий bind без сети (match зовётся на каждый добавляемый тайтл).
    // Прогресс с сайта подтянется в refresh() (RefreshTracks) с merge-by-max.
    override suspend fun match(manga: Manga): TrackSearch = TrackSearch.create(id).apply {
        title = manga.title
        tracking_url = manga.url
    }

    override fun isTrackFrom(track: DomainTrack, manga: Manga, source: Source?): Boolean =
        track.remoteUrl == manga.url && source?.let { accept(it) } == true

    override fun migrateTrack(track: DomainTrack, manga: Manga, newSource: Source): DomainTrack? =
        if (accept(newSource)) track.copy(remoteUrl = manga.url) else null
}
