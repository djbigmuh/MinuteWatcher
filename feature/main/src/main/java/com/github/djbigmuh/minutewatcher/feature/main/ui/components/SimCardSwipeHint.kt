package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.android.ui.HorizontalSwipeDirection
import com.github.djbigmuh.minutewatcher.core.android.ui.SwipeHintSide
import com.github.djbigmuh.minutewatcher.core.android.ui.resolveSwipeHintSide
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSpacing
import com.github.djbigmuh.minutewatcher.core.resources.R

private val SWIPE_HINT_EDGE_PADDING = 12.dp
private val SWIPE_HINT_ICON_SIZE = 20.dp

/**
 * Renders a visible, localized swipe affordance at the screen edge where navigation starts.
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
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f),
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
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SWIPE_HINT_EDGE_PADDING),
            contentAlignment = alignment,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                ),
                shadowElevation = 6.dp,
                modifier = Modifier.testTag("swipe_hint_indicator"),
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = MwSpacing.md,
                        vertical = MwSpacing.sm,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MwSpacing.sm),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_swipe_hint),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .size(SWIPE_HINT_ICON_SIZE)
                            .rotate(rotationDegrees),
                    )
                    Text(
                        text = stringResource(id = R.string.swipe_hint_content_desc),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
