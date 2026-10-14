package com.example.minutewatcher.core.android

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RobolectricSdkSmokeTest {
    @Test
    fun `verify Robolectric runs on expected SDK 37`() {
        assertEquals(37, Build.VERSION.SDK_INT)
    }
}
