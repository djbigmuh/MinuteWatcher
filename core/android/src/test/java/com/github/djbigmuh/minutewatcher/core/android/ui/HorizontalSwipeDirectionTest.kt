package com.github.djbigmuh.minutewatcher.core.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalSwipeDirectionTest {
    @Test
    fun `resolveNavigationSwipeDirection returns RIGHT_TO_LEFT when navigateForward is true and isRtl is false`() {
        val result = resolveNavigationSwipeDirection(navigateForward = true, isRtl = false)
        assertEquals(HorizontalSwipeDirection.RIGHT_TO_LEFT, result)
    }

    @Test
    fun `resolveNavigationSwipeDirection returns LEFT_TO_RIGHT when navigateForward is true and isRtl is true`() {
        val result = resolveNavigationSwipeDirection(navigateForward = true, isRtl = true)
        assertEquals(HorizontalSwipeDirection.LEFT_TO_RIGHT, result)
    }

    @Test
    fun `resolveNavigationSwipeDirection returns LEFT_TO_RIGHT when navigateForward is false and isRtl is false`() {
        val result = resolveNavigationSwipeDirection(navigateForward = false, isRtl = false)
        assertEquals(HorizontalSwipeDirection.LEFT_TO_RIGHT, result)
    }

    @Test
    fun `resolveNavigationSwipeDirection returns RIGHT_TO_LEFT when navigateForward is false and isRtl is true`() {
        val result = resolveNavigationSwipeDirection(navigateForward = false, isRtl = true)
        assertEquals(HorizontalSwipeDirection.RIGHT_TO_LEFT, result)
    }

    @Test
    fun `resolveSwipeHintSide returns END when direction is RIGHT_TO_LEFT and isRtl is false`() {
        val result = resolveSwipeHintSide(HorizontalSwipeDirection.RIGHT_TO_LEFT, isRtl = false)
        assertEquals(SwipeHintSide.END, result)
    }

    @Test
    fun `resolveSwipeHintSide returns START when direction is RIGHT_TO_LEFT and isRtl is true`() {
        val result = resolveSwipeHintSide(HorizontalSwipeDirection.RIGHT_TO_LEFT, isRtl = true)
        assertEquals(SwipeHintSide.START, result)
    }

    @Test
    fun `resolveSwipeHintSide returns START when direction is LEFT_TO_RIGHT and isRtl is false`() {
        val result = resolveSwipeHintSide(HorizontalSwipeDirection.LEFT_TO_RIGHT, isRtl = false)
        assertEquals(SwipeHintSide.START, result)
    }

    @Test
    fun `resolveSwipeHintSide returns END when direction is LEFT_TO_RIGHT and isRtl is true`() {
        val result = resolveSwipeHintSide(HorizontalSwipeDirection.LEFT_TO_RIGHT, isRtl = true)
        assertEquals(SwipeHintSide.END, result)
    }
}
