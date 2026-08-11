package com.example.minutewatcher.feature.widget

import android.content.Context
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetUpdaterImplTest {
    private val context: Context = mock()
    private val appSettingsRepository: AppSettingsRepository = mock()

    private lateinit var updater: WidgetUpdaterImpl

    @Before
    fun setup() {
        updater =
            WidgetUpdaterImpl(
                context = context,
                appSettingsRepository = appSettingsRepository,
            )
    }

    @Test
    fun `isRemoteViewsLimitException returns false for generic IllegalArgumentException`() {
        val e =
            IllegalArgumentException(
                "Something else went wrong",
            )
        assertFalse(
            updater.isRemoteViewsLimitException(
                e,
            ),
        )
    }

    @Test
    fun `isRemoteViewsLimitException returns true for TransactionTooLargeException cause`() {
        val cause = android.os.TransactionTooLargeException()
        val e =
            IllegalArgumentException(
                "Failure sending RemoteViews",
                cause,
            )
        assertTrue(
            updater.isRemoteViewsLimitException(
                e,
            ),
        )
    }

    @Test
    fun `isRemoteViewsLimitException returns true for RuntimeException with TransactionTooLargeException cause`() {
        val cause = android.os.TransactionTooLargeException()
        val e = RuntimeException("Failure sending RemoteViews", cause)
        assertTrue(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns false when stack trace contains only RemoteViews without exception`() {
        val e =
            IllegalArgumentException(
                "Fake exception",
            )
        e.stackTrace =
            arrayOf(
                StackTraceElement(
                    "android.widget.RemoteViews",
                    "writeToParcel",
                    "RemoteViews.java",
                    100,
                ),
            )
        assertFalse(
            updater.isRemoteViewsLimitException(
                e,
            ),
        )
    }

    @Test
    fun `isRemoteViewsLimitException returns true for deeply nested TransactionTooLargeException cause`() {
        val cause = android.os.TransactionTooLargeException()
        val wrapper = RuntimeException("Middle wrapper", cause)
        val e = IllegalArgumentException("Top level", wrapper)
        assertTrue(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns false for generic Binder failure`() {
        val e = IllegalArgumentException("Something failed in binder")
        assertFalse(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `updateAll returns false when performWidgetUpdate throws unexpected exception`() =
        runTest {
            val failingUpdater =
                object : WidgetUpdaterImpl(context, appSettingsRepository) {
                    override suspend fun performWidgetUpdate(context: Context) {
                        error("Unexpected crash")
                    }
                }

            val result = failingUpdater.updateAll()

            assertFalse("updateAll should return false on unexpected exception", result)
        }

    @Test
    fun `updateAll calls performWidgetUpdate twice when LimitExceeded occurs`() =
        runTest {
            var callCount = 0
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                @Suppress("UNCHECKED_CAST")
                val transform = it.arguments[0] as (AppSettings) -> AppSettings
                mockSettings.value = transform(mockSettings.value)
            }

            val updater =
                object : WidgetUpdaterImpl(context, appSettingsRepository) {
                    override suspend fun performWidgetUpdate(context: Context) {
                        callCount++
                        if (callCount == 1) {
                            // Simulate LimitExceeded on first call
                            throw IllegalArgumentException(
                                "RemoteViews too large",
                                android.os.TransactionTooLargeException(),
                            )
                        }
                    }
                }

            val result = updater.updateAll()

            assertTrue("updateAll should return true even on LimitExceeded", result)
            assertEquals("performWidgetUpdate should be called twice", 2, callCount)
            assertTrue("widgetLimitExceeded flag should be set", mockSettings.value.widgetLimitExceeded)
        }
}
