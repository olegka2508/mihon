package eu.kanade.tachiyomi.data.track

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.remanga.Remanga
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Форк: разовая выгрузка всего прочитанного прогресса на трекеры (Mihon → сайты) как
 * фоновая задача — с полосой прогресса (foreground) и отчётом-уведомлением по завершении,
 * по образцу [eu.kanade.tachiyomi.data.library.LibraryUpdateJob]. Переживает сворачивание.
 *
 * По каждому тайтлу берём макс. прочитанную главу и пушим напрямую во все привязанные
 * enhanced-трекеры (bindEnhancedTrackers создаёт запись, если её нет). Remanga метит полную
 * глубину (pushAllRead), MangaLib — фронтир (сайт заполняет между сам). Последовательно —
 * щадим DDoS-Guard. Отчёт разбит по трекерам + тексты ошибок, чтобы причина была видна.
 */
class TrackerBulkPushJob(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val getChapters: GetChaptersByMangaId = Injekt.get()
    private val getTracks: GetTracks = Injekt.get()
    private val addTracks: AddTracks = Injekt.get()
    private val trackerManager: TrackerManager = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        Notifications.ID_TRACKER_BULK_PROGRESS,
        progressNotification(0, 0),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        },
    )

    override suspend fun doWork(): Result {
        setForegroundSafely()

        val library = getLibraryManga.await().distinctBy { it.manga.id }
        val total = library.size
        val ok = HashMap<String, Int>()
        val err = HashMap<String, Int>()
        val errMsgs = LinkedHashMap<String, Int>()

        library.forEachIndexed { index, libManga ->
            if (isStopped) return Result.success() // пользователь отменил
            val manga = libManga.manga
            context.notify(Notifications.ID_TRACKER_BULK_PROGRESS, progressNotification(index + 1, total))

            val maxRead = getChapters.await(manga.id)
                .filter { it.read }
                .maxOfOrNull { it.chapterNumber }
                ?: return@forEachIndexed
            if (maxRead <= 0.0) return@forEachIndexed

            // Привязать enhanced-трекеры, если ещё не привязан (идемпотентно).
            runCatching { addTracks.bindEnhancedTrackers(manga, sourceManager.getOrStub(manga.source)) }

            getTracks.await(manga.id).forEach { track ->
                val tracker = trackerManager.get(track.trackerId) ?: return@forEach
                if (!tracker.isLoggedIn) return@forEach
                val dbTrack = track.copy(lastChapterRead = maxRead).toDbTrack()
                runCatching {
                    when (tracker) {
                        // Remanga: полная глубина — закрывает и пробелы ниже сайтового указателя
                        is Remanga -> tracker.pushAllRead(dbTrack)
                        // MangaLib и пр.: сайт заполняет между сам при сдвиге указателя
                        is EnhancedTracker -> tracker.update(dbTrack, didReadChapter = true)
                        else -> return@forEach
                    }
                }.onSuccess {
                    ok[tracker.name] = (ok[tracker.name] ?: 0) + 1
                }.onFailure {
                    err[tracker.name] = (err[tracker.name] ?: 0) + 1
                    val msg = "${tracker.name}: ${it.message ?: it.javaClass.simpleName}"
                    errMsgs[msg] = (errMsgs[msg] ?: 0) + 1
                    logcat(LogPriority.WARN, it) { "Bulk push: ${manga.title} → ${tracker.name} не отправлен" }
                }
            }
        }

        context.cancelNotification(Notifications.ID_TRACKER_BULK_PROGRESS)
        showReport(ok, err, errMsgs)
        return Result.success()
    }

    private fun progressNotification(current: Int, total: Int) =
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.tracker_bulk_push_progress))
            if (total > 0) setContentText("$current / $total")
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(total, current, total == 0)
        }.build()

    /** Отчёт-уведомление: разбивка по трекерам «MangaLib 69/0, Remanga 0/53» + тексты ошибок. */
    private fun showReport(ok: Map<String, Int>, err: Map<String, Int>, errMsgs: Map<String, Int>) {
        val names = (ok.keys + err.keys).toSortedSet()
        val head = if (names.isEmpty()) {
            context.stringResource(MR.strings.tracker_bulk_push_none)
        } else {
            names.joinToString(", ") { "$it ${ok[it] ?: 0}/${err[it] ?: 0}" }
        }
        val body = buildString {
            append(head)
            if (errMsgs.isNotEmpty()) {
                append("\n\n")
                append(context.stringResource(MR.strings.tracker_bulk_push_errors))
                errMsgs.entries.sortedByDescending { it.value }.take(8).forEach { (msg, count) ->
                    append("\n").append(msg).append(" ×").append(count)
                }
            }
        }
        context.notify(
            Notifications.ID_TRACKER_BULK_COMPLETE,
            context.notificationBuilder(Notifications.CHANNEL_COMMON) {
                setContentTitle(context.stringResource(MR.strings.tracker_bulk_push_done))
                setContentText(head)
                setStyle(NotificationCompat.BigTextStyle().bigText(body))
                setSmallIcon(R.drawable.ic_done_24dp)
                setAutoCancel(true)
            }.build(),
        )
    }

    companion object {
        private const val WORK_NAME = "TrackerBulkPush"

        /** Ставит разовую выгрузку в очередь; KEEP — повторный тап не плодит параллельные проходы. */
        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrackerBulkPushJob>()
                .addTag(WORK_NAME)
                .build()
            context.workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
