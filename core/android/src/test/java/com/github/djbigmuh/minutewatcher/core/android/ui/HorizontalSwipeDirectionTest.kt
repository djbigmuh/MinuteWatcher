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
}
