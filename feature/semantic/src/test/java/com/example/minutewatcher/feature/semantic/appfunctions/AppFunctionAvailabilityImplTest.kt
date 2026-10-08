package com.example.minutewatcher.feature.semantic.appfunctions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppFunctionAvailabilityImplTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    @Config(sdk = [35]) // behavior-specific SDK
    fun `set assistant access enabled below api 36 is no op`() =
        runTest {
            val availability = AppFunctionAvailabilityImpl(context)
            availability.setAssistantAccessEnabled(true)
        }

    @Test
    @Config(sdk = [36]) // behavior-specific SDK
    fun `set assistant access enabled on api 36 handles missing platform service gracefully`() =
        runTest {
            val availability = AppFunctionAvailabilityImpl(context)
            availability.setAssistantAccessEnabled(true)
            availability.setAssistantAccessEnabled(false)
        }
}
