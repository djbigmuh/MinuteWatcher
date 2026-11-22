package com.github.djbigmuh.minutewatcher.core.android.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

enum class HorizontalSwipeDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
}

enum class SwipeHintSide {
    START,
    END,
}

/**
 * Resolves the physical horizontal swipe direction based on whether the action is navigating forward
 * or backward and whether the current layout direction is right-to-left (RTL).
 *
 * Truth Table:
 * - [navigateForward] = true,  [isRtl] = false -> [HorizontalSwipeDirection.RIGHT_TO_LEFT]
 * - [navigateForward] = true,  [isRtl] = true  -> [HorizontalSwipeDirection.LEFT_TO_RIGHT]
 * - [navigateForward] = false, [isRtl] = false -> [HorizontalSwipeDirection.LEFT_TO_RIGHT]
 * - [navigateForward] = false, [isRtl] = true  -> [HorizontalSwipeDirection.RIGHT_TO_LEFT]
 *
 * @param navigateForward `true` if swiping to navigate forward (next screen), `false` if navigating backward.
 * @param isRtl `true` if the layout direction is Right-to-Left, `false` otherwise.
 * @return The resolved [HorizontalSwipeDirection] for the swipe gesture.
 */
fun resolveNavigationSwipeDirection(
    navigateForward: Boolean,
    isRtl: Boolean,
): HorizontalSwipeDirection =
    if (navigateForward) {
        if (isRtl) HorizontalSwipeDirection.LEFT_TO_RIGHT else HorizontalSwipeDirection.RIGHT_TO_LEFT
    } else {
        if (isRtl) HorizontalSwipeDirection.RIGHT_TO_LEFT else HorizontalSwipeDirection.LEFT_TO_RIGHT
    }

/**
 * Resolves the side of the screen ([SwipeHintSide.START] or [SwipeHintSide.END]) where the swipe hint
 * indicator should be placed based on the layout direction and the supported swipe gesture direction.
 *
 * Truth Table:
 * - [direction] = RIGHT_TO_LEFT, [isRtl] = false -> [SwipeHintSide.END]
 * - [direction] = RIGHT_TO_LEFT, [isRtl] = true  -> [SwipeHintSide.START]
 * - [direction] = LEFT_TO_RIGHT, [isRtl] = false -> [SwipeHintSide.START]
 * - [direction] = LEFT_TO_RIGHT, [isRtl] = true  -> [SwipeHintSide.END]
 *
 * @param direction The supported horizontal swipe direction for navigation.
 * @param isRtl `true` if the layout direction is Right-to-Left, `false` otherwise.
 * @return The resolved [SwipeHintSide] for positioning the hint indicator.
 */
fun resolveSwipeHintSide(
    direction: HorizontalSwipeDirection,
    isRtl: Boolean,
): SwipeHintSide =
    when (direction) {
        HorizontalSwipeDirection.RIGHT_TO_LEFT -> if (isRtl) SwipeHintSide.START else SwipeHintSide.END
        HorizontalSwipeDirection.LEFT_TO_RIGHT -> if (isRtl) SwipeHintSide.END else SwipeHintSide.START
    }

fun resolveSwipeHintSide(
    direction: HorizontalSwipeDirection,
    layoutDirection: LayoutDirection,
): SwipeHintSide = resolveSwipeHintSide(direction, layoutDirection == LayoutDirection.Rtl)

fun Modifier.onHorizontalSwipe(
    direction: HorizontalSwipeDirection,
    threshold: Dp,
    onSwipe: () -> Unit,
): Modifier = this.then(HorizontalSwipeNodeElement(direction, threshold, onSwipe))

private data class HorizontalSwipeNodeElement(
    val direction: HorizontalSwipeDirection,
    val threshold: Dp,
    val onSwipe: () -> Unit,
) : ModifierNodeElement<HorizontalSwipeNode>() {
    override fun create(): HorizontalSwipeNode = HorizontalSwipeNode(direction, threshold, onSwipe)

    override fun update(node: HorizontalSwipeNode) {
        node.update(direction, threshold, onSwipe)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "onHorizontalSwipe"
        properties["direction"] = direction
        properties["threshold"] = threshold
        properties["onSwipe"] = onSwipe
    }
}

private class HorizontalSwipeNode(
    var direction: HorizontalSwipeDirection,
    var threshold: Dp,
    var onSwipe: () -> Unit,
) : DelegatingNode(),
    PointerInputModifierNode {
    private val pointerInputNode =
        delegate(
            SuspendingPointerInputModifierNode {
                detectSwipe()
            },
        )

    fun update(
        direction: HorizontalSwipeDirection,
        threshold: Dp,
        onSwipe: () -> Unit,
    ) {
        val oldDirection = this.direction
        val oldThreshold = this.threshold
        this.direction = direction
        this.threshold = threshold
        this.onSwipe = onSwipe

        if (oldDirection != direction || oldThreshold != threshold) {
            pointerInputNode.resetPointerInputHandler()
        }
    }

    private suspend fun PointerInputScope.detectSwipe() {
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
                    onSwipe()
                }
            },
            onDragCancel = { totalDrag = 0f },
            onHorizontalDrag = { _, dragAmount ->
                totalDrag += dragAmount
            },
        )
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        pointerInputNode.onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        pointerInputNode.onCancelPointerInput()
    }
}
