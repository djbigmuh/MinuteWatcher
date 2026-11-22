package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.android.ui.HorizontalSwipeDirection
import com.github.djbigmuh.minutewatcher.core.android.ui.SwipeHintSide
import com.github.djbigmuh.minutewatcher.core.android.ui.resolveSwipeHintSide
import com.github.djbigmuh.minutewatcher.core.resources.R

private val SWIPE_HINT_SIZE = 24.dp
private val SWIPE_HINT_EDGE_PADDING = 16.dp
private const val SWIPE_HINT_ALPHA = 0.6f

/**
 * Renders a subtle swipe affordance hint indicator at the screen edge where the navigation swipe gesture starts.
 *
 * @param isSwipeAvailable Whether swipe navigation is currently available on the screen.
 * @param isSwipeHintDismissed Whether the hint has already been dismissed by the user.
 * @param swipeDirection The supported horizontal swipe navigation direction.
 * @param modifier Additional modifier for the hint container.
 */
@Composable
fun SimCardSwipeHint(
    isSwipeAvailable: Boolean,
    isSwipeHintDismissed: Boolean,
    swipeDirection: HorizontalSwipeDirection,
    modifier: Modifier = Modifier,
) {
    val showHint = isSwipeAvailable && !isSwipeHintDismissed

    AnimatedVisibility(
        visible = showHint,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize(),
    ) {
        val layoutDirection = LocalLayoutDirection.current
        val hintSide = resolveSwipeHintSide(swipeDirection, layoutDirection)

        val alignment =
            when (hintSide) {
                SwipeHintSide.START -> Alignment.CenterStart
                SwipeHintSide.END -> Alignment.CenterEnd
            }

        val rotationDegrees =
            when (swipeDirection) {
                HorizontalSwipeDirection.RIGHT_TO_LEFT -> 0f
                HorizontalSwipeDirection.LEFT_TO_RIGHT -> 180f
            }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = SWIPE_HINT_EDGE_PADDING),
            contentAlignment = alignment,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_swipe_hint),
                contentDescription = stringResource(id = R.string.swipe_hint_content_desc),
                tint = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .size(SWIPE_HINT_SIZE)
                        .rotate(rotationDegrees)
                        .graphicsLayer { alpha = SWIPE_HINT_ALPHA }
                        .testTag("swipe_hint_indicator"),
            )
        }
    }
}
