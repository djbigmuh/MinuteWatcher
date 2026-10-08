package com.example.minutewatcher.feature.semantic.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AppSearchWrapperTest {
    private val wrapper = AppSearchWrapperImpl()

    @Test
    @Config(sdk = [28])
    fun `isPlatformBackend returns false on API 28`() {
        assertFalse(wrapper.isPlatformBackend())
    }

    @Test
    @Config(sdk = [31])
    fun `isPlatformBackend returns true on API 31`() {
        assertTrue(wrapper.isPlatformBackend())
    }
}
