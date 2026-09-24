package eu.kanade.tachiyomi.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen

/** History keeps its existing data and actions, but returns to the screen that opened it. */
data object HistoryDetailScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val back: () -> Unit = { navigator.pop() }
        CompositionLocalProvider(LocalBackPress provides back) {
            HistoryTab.Content()
        }
    }
}
