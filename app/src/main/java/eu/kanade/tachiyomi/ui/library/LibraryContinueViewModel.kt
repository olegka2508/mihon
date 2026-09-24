package eu.kanade.tachiyomi.ui.library

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibraryContinueViewModel(
    private val historyRepository: HistoryRepository = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
) : StateViewModel<LibraryContinueViewModel.State>(State()) {

    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launchIO {
            try {
                val history = historyRepository.getLastHistory()
                val chapter = history?.let {
                    getNextChapters.await(it.mangaId, it.chapterId, onlyUnread = false).firstOrNull()
                }
                mutableState.update { State(history, chapter) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.ERROR, error)
                mutableState.update { State() }
            }
        }
    }

    data class State(
        val history: HistoryWithRelations? = null,
        val chapter: Chapter? = null,
    )
}
