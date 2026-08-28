package com.example.minutewatcher.feature.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetUpdaterImplTest {
    private lateinit var context: Context
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val renderer: WidgetRenderer = mock()
    private val retryManager = WidgetUpdateRetryManager()
    private val limitManager = WidgetLimitManager(appSettingsRepository)
    private val errorClassifier = WidgetErrorClassifier()

    private lateinit var updater: WidgetUpdaterImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        updater =
            WidgetUpdaterImpl(
                context = context,
                appSettingsRepository = appSettingsRepository,
                renderer = renderer,
                retryManager = retryManager,
                limitManager = limitManager,
                errorClassifier = errorClassifier,
            )
    }

    @Test
    fun `updateAll calls renderer with correct parameters`() =
        runTest {
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            updater.updateAll()

            verify(renderer).renderAll(
                context = eq(context),
                forceFull = any(),
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
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            val failingUpdater =
                WidgetUpdaterImpl(
                    context,
                    appSettingsRepository,
                    renderer =
                        object : WidgetRenderer {
                            override suspend fun renderAll(
                                context: Context,
                                forceFull: Boolean,
                            ) {
                                error("Unexpected crash")
                            }
                        },
                    retryManager = retryManager,
                    limitManager = limitManager,
                    errorClassifier = errorClassifier,
                )

            val result = failingUpdater.updateAll()

            assertFalse("updateAll should return false on unexpected exception", result)
        }

    @Test
    fun `updateAll calls performWidgetUpdate twice when LimitExceeded occurs`() =
        runTest {
            var callCount = 0
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)
            @Suppress("UNCHECKED_CAST")
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                val transform = it.arguments[0] as (AppSettings) -> AppSettings
                mockSettings.value = transform(mockSettings.value)
            }

            val updater =
                WidgetUpdaterImpl(
                    context,
                    appSettingsRepository,
                    renderer =
                        object : WidgetRenderer {
                            override suspend fun renderAll(
                                context: Context,
                                forceFull: Boolean,
                            ) {
                                callCount++
                                if (callCount == 1) {
                                    // Simulate LimitExceeded on first call
                                    throw IllegalArgumentException(
                                        "RemoteViews too large",
                                        android.os.TransactionTooLargeException(),
                                    )
                                }
                            }
                        },
                    retryManager = retryManager,
                    limitManager = limitManager,
                    errorClassifier = errorClassifier,
                )

            val result = updater.updateAll()

            assertTrue("updateAll should return true even on LimitExceeded", result)
            assertEquals("performWidgetUpdate should be called twice", 2, callCount)
            assertTrue("widgetLimitExceeded flag should be set", mockSettings.value.widgetLimitExceeded)
        }

    @Test
    fun `updateAll uses limitManager to check for probing when limit is exceeded`() =
        runTest {
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = true))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            val mockLimitManager: WidgetLimitManager = mock()
            whenever(mockLimitManager.shouldProbe()).thenReturn(true)
            @Suppress("UNCHECKED_CAST")
            whenever(mockLimitManager.performProbeUpdate(any(), any())).thenAnswer {
                runBlocking {
                    val updateBlock = it.arguments[1] as suspend (Context, Boolean) -> UpdateResult
                    updateBlock(context, true)
                }
            }

            val updaterWithMock =
                WidgetUpdaterImpl(
                    context = context,
                    appSettingsRepository = appSettingsRepository,
                    renderer = renderer,
                    retryManager = retryManager,
                    limitManager = mockLimitManager,
                    errorClassifier = errorClassifier,
                )

            updaterWithMock.updateAll()

            verify(mockLimitManager).shouldProbe()
            verify(mockLimitManager).performProbeUpdate(eq(context), any())
        }

    @Test
    fun `updateAll returns false and does not retry on SecurityException`() =
        runTest {
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            whenever(renderer.renderAll(any(), any())).thenThrow(SecurityException("No permission"))

            val result = updater.updateAll()

            assertFalse("updateAll should return false on SecurityException", result)
            verify(renderer).renderAll(any(), any()) // Should be called only once
        }

    @Test
    fun `updateAll returns false and does not retry on Error`() =
        runTest {
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            whenever(renderer.renderAll(any(), any())).thenThrow(OutOfMemoryError("Out of memory"))

            val result = updater.updateAll()

            assertFalse("updateAll should return false on Error", result)
            verify(renderer).renderAll(any(), any()) // Should be called only once
        }

    @Test
    fun `handleLimitExceeded proceeds to simplified update on settings timeout`() =
        runTest {
            var callCount = 0
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(emptyFlow())
            @Suppress("UNCHECKED_CAST")
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                val transform = it.arguments[0] as (AppSettings) -> AppSettings
                mockSettings.value = transform(mockSettings.value)
            }

            val updater =
                WidgetUpdaterImpl(
                    context,
                    appSettingsRepository,
                    renderer =
                        object : WidgetRenderer {
                            override suspend fun renderAll(
                                context: Context,
                                forceFull: Boolean,
                            ) {
                                callCount++
                                if (callCount == 1) {
                                    // Simulate LimitExceeded on first call
                                    throw IllegalArgumentException(
                                        "RemoteViews too large",
                                        android.os.TransactionTooLargeException(),
                                    )
                                }
                            }
                        },
                    retryManager = retryManager,
                    limitManager = limitManager,
                    errorClassifier = errorClassifier,
                )

            val result = updater.updateAll()

            assertTrue("updateAll should return true even on LimitExceeded with timeout", result)
            assertEquals("performWidgetUpdate should be called twice", 2, callCount)
            assertFalse(
                "widgetLimitExceeded flag should NOT be set due to timeout",
                mockSettings.value.widgetLimitExceeded,
            )
        }

    @Test
    fun `handleLimitExceeded returns Stop if already exceeded and not timed out`() =
        runTest {
            var callCount = 0
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = true))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            val updater =
                WidgetUpdaterImpl(
                    context,
                    appSettingsRepository,
                    renderer =
                        object : WidgetRenderer {
                            override suspend fun renderAll(
                                context: Context,
                                forceFull: Boolean,
                            ) {
                                callCount++
                                throw IllegalArgumentException(
                                    "RemoteViews too large",
                                    android.os.TransactionTooLargeException(),
                                )
                            }
                        },
                    retryManager = retryManager,
                    limitManager = limitManager,
                    errorClassifier = errorClassifier,
                )

            val result = updater.updateAll()

            assertFalse("updateAll should return false if simplified update fails and already exceeded", result)
            assertEquals("performWidgetUpdate should be called only once", 1, callCount)
        }

    @Test
    fun `updateAll recovers after LimitExceeded via probe update`() =
        runTest {
            var callCount = 0
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)
            @Suppress("UNCHECKED_CAST")
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                val transform = it.arguments[0] as (AppSettings) -> AppSettings
                mockSettings.value = transform(mockSettings.value)
            }

            // 1. First call: LimitExceeded
            whenever(renderer.renderAll(any(), any())).thenAnswer {
                callCount++
                throw IllegalArgumentException("Too large", android.os.TransactionTooLargeException())
            }

            updater.updateAll()
            assertTrue("Limit flag should be set", mockSettings.value.widgetLimitExceeded)
            assertEquals(2, callCount) // Full attempt + simplified fallback

            // 2. Second call: success on probe
            val mockLimitManager: WidgetLimitManager = mock()
            whenever(mockLimitManager.shouldProbe()).thenReturn(true)
            @Suppress("UNCHECKED_CAST")
            whenever(mockLimitManager.performProbeUpdate(any(), any())).thenAnswer {
                runBlocking {
                    val updateBlock = it.arguments[1] as suspend (Context, Boolean) -> UpdateResult
                    updateBlock(context, true)
                }
            }

            val updaterWithMock =
                WidgetUpdaterImpl(
                    context,
                    appSettingsRepository,
                    renderer,
                    retryManager,
                    mockLimitManager,
                    errorClassifier,
                )

            whenever(renderer.renderAll(any(), eq(true))).thenReturn(Unit) // Success on probe

            val result = updaterWithMock.updateAll()

            assertTrue("Probe update should succeed", result)
            verify(mockLimitManager).setWidgetLimitExceeded(false)
        }
}
