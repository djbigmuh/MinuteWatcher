package com.example.minutewatcher.core.android.ui

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

enum class HorizontalSwipeDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
}

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
