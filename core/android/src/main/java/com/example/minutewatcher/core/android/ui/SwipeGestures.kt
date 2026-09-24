@file:Suppress("MatchingDeclarationName")

package com.example.minutewatcher.core.android.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp

enum class HorizontalSwipeDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
}

fun Modifier.onHorizontalSwipe(
    direction: HorizontalSwipeDirection,
    threshold: Dp,
    onSwipe: () -> Unit,
): Modifier =
    composed {
        val currentOnSwipe by rememberUpdatedState(onSwipe)
        pointerInput(direction, threshold) {
            val thresholdPx = threshold.toPx()
            var totalDrag = 0f
            detectHorizontalDragGestures(
                onDragStart = { totalDrag = 0f },
                onDragEnd = {
                    val triggered =
                        when (direction) {
                            HorizontalSwipeDirection.RIGHT_TO_LEFT -> totalDrag <= -thresholdPx
                            HorizontalSwipeDirection.LEFT_TO_RIGHT -> totalDrag >= thresholdPx
                        }
                    totalDrag = 0f
                    if (triggered) {
                        currentOnSwipe()
                    }
                },
                onDragCancel = { totalDrag = 0f },
                onHorizontalDrag = { _, dragAmount ->
                    totalDrag += dragAmount
                },
            )
        }
    }
