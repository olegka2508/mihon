package eu.kanade.presentation.util

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/** Shared timing for navigation. Compose handles the system animation duration scale. */
internal object ReaderMotion {
    const val TAB_DURATION = 230
    const val SCREEN_DURATION = 250
    const val PANEL_DURATION = 260

    val easing = CubicBezierEasing(0.22f, 0.6f, 0.28f, 1f)

    fun transition(
        forward: Boolean,
        distancePx: Int,
        durationMillis: Int = SCREEN_DURATION,
    ): ContentTransform {
        val direction = if (forward) 1 else -1
        return (
            slideInHorizontally(tween(durationMillis, easing = easing)) { direction * distancePx } +
                fadeIn(tween(180))
            ) togetherWith (
            slideOutHorizontally(tween(160, easing = easing)) { -direction * distancePx / 2 } +
                fadeOut(tween(120))
            )
    }
}
