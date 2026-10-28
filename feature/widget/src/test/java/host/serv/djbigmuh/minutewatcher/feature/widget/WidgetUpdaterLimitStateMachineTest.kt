package host.serv.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import android.os.TransactionTooLargeException
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdaterLimitStateMachineTest : BaseWidgetUpdaterFixture() {
    @Test
    fun `updateAll calls performWidgetUpdate twice when LimitExceeded occurs`() =
        runTest {
            var callCount = 0
            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        callCount++
                        if (callCount == 1) {
                            throw IllegalArgumentException(
                                "RemoteViews too large",
                                TransactionTooLargeException(),
                            )
                        }
                    }
                }
            val updater = createUpdater(backgroundScope, rendererMock)
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            val result = updater.updateAll()

            assertTrue("updateAll should return success even on LimitExceeded", result.isSuccess)
            assertEquals("performWidgetUpdate should be called twice", 2, callCount)
            assertTrue(
                "widgetLimitExceeded flag should persist after simplified fallback",
                appSettingsRepository.settingsFlow.value.widgetLimitExceeded,
            )
        }

    @Test
    fun `handleLimitExceeded sets flag and attempts simplified update`() =
        runTest {
            var callCount = 0
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        callCount++
                        if (callCount == 1) {
                            assertTrue("1st attempt should be FULL", forceFull)
                            throw IllegalArgumentException("Too large", TransactionTooLargeException())
                        }
                        if (callCount == 2) {
                            assertFalse("2nd attempt (fallback) should be SIMPLIFIED", forceFull)
                        }
                    }
                }

            val updater = createUpdater(backgroundScope, rendererMock)

            val result = updater.updateAll()

            assertTrue("updateAll should succeed after simplified fallback", result.isSuccess)
            assertEquals(2, callCount)
            assertTrue(
                "Limit flag should NOT be reset after successful simplified update",
                appSettingsRepository.settingsFlow.value.widgetLimitExceeded,
            )
        }

    @Test
    fun `updateAll returns TRANSIENT failure if simplified fallback fails with retryable error`() =
        runTest {
            var callCount = 0
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        callCount++
                        if (callCount == 1) {
                            throw IllegalArgumentException("Too large", TransactionTooLargeException())
                        } else {
                            throw TestCrashException("Simplified also failed")
                        }
                    }
                }
            val updater = createUpdater(backgroundScope, rendererMock)

            val result = updater.updateAll()

            assertFalse(result.isSuccess)
            val failure = result as WidgetUpdateResult.Failure
            assertTrue("Should be retryable", failure.isRetryable)
            assertEquals(WidgetUpdateResult.FailureReason.TRANSIENT, failure.reason)
        }

    @Test
    fun `updateAll returns LIMIT_EXCEEDED failure if simplified fallback also exceeds limit`() =
        runTest {
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ): Unit = throw IllegalArgumentException("Too large", TransactionTooLargeException())
                }
            val updater = createUpdater(backgroundScope, rendererMock)

            val result = updater.updateAll()

            assertFalse(result.isSuccess)
            val failure = result as WidgetUpdateResult.Failure
            assertFalse("Should not be retryable", failure.isRetryable)
            assertEquals(WidgetUpdateResult.FailureReason.LIMIT_EXCEEDED, failure.reason)
        }

    @Test
    fun `updateAll recovers after LimitExceeded via probe update`() =
        runTest {
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            var callCount = 0
            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        callCount++
                        throw IllegalArgumentException("Too large", TransactionTooLargeException())
                    }
                }
            val updaterFirst = createUpdater(backgroundScope, rendererMock)

            updaterFirst.updateAll()
            assertTrue("Limit flag should be set", appSettingsRepository.settingsFlow.value.widgetLimitExceeded)
            assertEquals(2, callCount)

            val mockLimitManager: WidgetLimitManager = mock()
            whenever(mockLimitManager.isLimitExceeded()).thenReturn(true)
            whenever(mockLimitManager.shouldProbe()).thenReturn(true)
            whenever(mockLimitManager.performProbeUpdate(any(), any())).thenAnswer {
                UpdateResult.Success(wasFull = true)
            }

            val updaterWithMock =
                WidgetUpdaterImpl(
                    context,
                    appSettingsRepository,
                    renderer,
                    retryManager,
                    mockLimitManager,
                    errorClassifier,
                    scope = backgroundScope,
                )

            val result = updaterWithMock.updateAll()

            assertTrue("Probe update should succeed", result.isSuccess)
            verify(mockLimitManager).setWidgetLimitExceeded(value = false)
        }

    @Test
    fun `updateAll stays in SIMPLIFIED mode if simplified update fails after LimitExceeded`() =
        runTest {
            var callCount = 0
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        callCount++
                        if (callCount == 1) {
                            throw IllegalArgumentException("Too large", TransactionTooLargeException())
                        } else {
                            error("Simplified failed")
                        }
                    }
                }

            val updater = createUpdater(backgroundScope, rendererMock)

            val result1 = updater.updateAll()
            assertFalse("First update should fail", result1.isSuccess)
            assertEquals(2, callCount)
            assertTrue("Limit flag should be set", appSettingsRepository.settingsFlow.value.widgetLimitExceeded)

            updater.updateAll()

            assertEquals("Should have called renderer again for simplified update", 3, callCount)
            assertTrue("Limit flag should still be set", appSettingsRepository.settingsFlow.value.widgetLimitExceeded)
        }

    @Test
    fun `handleLimitExceeded propagates CancellationException`() =
        runTest {
            var callCount = 0
            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        callCount++
                        if (callCount == 1) {
                            throw IllegalArgumentException("RemoteViews too large", TransactionTooLargeException())
                        } else {
                            throw CancellationException("Simulated cancellation")
                        }
                    }
                }
            val updater = createUpdater(backgroundScope, rendererMock)
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            try {
                updater.updateAll()
            } catch (e: CancellationException) {
                assertEquals("Simulated cancellation", e.message)
                return@runTest
            }
            error("Expected CancellationException to be thrown")
        }

    @Test
    fun `handleLimitExceeded is called only once until success`() =
        runTest {
            var simplifiedCallCount = 0
            var shouldFail = true
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        if (!forceFull) {
                            simplifiedCallCount++
                        }
                        if (shouldFail) {
                            throw IllegalArgumentException("Too large", TransactionTooLargeException())
                        }
                    }
                }

            val updater = createUpdater(backgroundScope, rendererMock)

            updater.updateAll()
            assertEquals(1, simplifiedCallCount)

            updater.updateAll()
            assertEquals("Should NOT retry simplified if it fails", 2, simplifiedCallCount)

            shouldFail = false
            updater.updateAll()
            assertEquals(3, simplifiedCallCount)

            updater.recover()

            shouldFail = true
            updater.updateAll()
            assertEquals(4, simplifiedCallCount)
        }

    @Test
    fun `state machine transition FULL to SIMPLIFIED to PROBE to FULL`() =
        runTest {
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            var shouldExceedLimit = false
            var fullRenderCount = 0
            var simplifiedRenderCount = 0

            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        if (forceFull) {
                            fullRenderCount++
                            if (shouldExceedLimit) {
                                throw IllegalArgumentException("Too large", TransactionTooLargeException())
                            }
                        } else {
                            simplifiedRenderCount++
                        }
                    }
                }

            val updater = createUpdater(backgroundScope, rendererMock)

            updater.updateAll()
            assertEquals(1, fullRenderCount)
            assertEquals(0, simplifiedRenderCount)
            assertFalse(appSettingsRepository.settingsFlow.value.widgetLimitExceeded)

            shouldExceedLimit = true
            updater.updateAll()
            assertEquals(2, fullRenderCount)
            assertEquals(1, simplifiedRenderCount)
            assertTrue(
                "Flag must be set after LimitExceeded",
                appSettingsRepository.settingsFlow.value.widgetLimitExceeded,
            )

            repeat(9) { updater.updateAll() }
            assertEquals(2, fullRenderCount)
            assertEquals(10, simplifiedRenderCount)

            updater.updateAll()
            assertEquals("10th update since limit should be a PROBE (FULL)", 3, fullRenderCount)
            assertEquals(11, simplifiedRenderCount)
            assertTrue(
                "Flag must persist during simplified cycles",
                appSettingsRepository.settingsFlow.value.widgetLimitExceeded,
            )

            updater.updateAll()
            assertEquals(3, fullRenderCount)
            assertEquals(12, simplifiedRenderCount)

            repeat(8) { updater.updateAll() }
            assertEquals(3, fullRenderCount)
            assertEquals(20, simplifiedRenderCount)

            updater.updateAll()
            assertEquals(4, fullRenderCount)
            assertEquals(21, simplifiedRenderCount)

            shouldExceedLimit = false

            repeat(9) { updater.updateAll() }
            assertEquals(4, fullRenderCount)
            assertEquals(30, simplifiedRenderCount)

            updater.updateAll()
            assertEquals("Successful probe should increment full count", 5, fullRenderCount)
            assertEquals(30, simplifiedRenderCount)
            assertFalse(
                "Flag must be cleared after successful FULL probe",
                appSettingsRepository.settingsFlow.value.widgetLimitExceeded,
            )

            updater.updateAll()
            assertEquals(6, fullRenderCount)
            assertEquals(30, simplifiedRenderCount)
        }

    @Test
    fun `updateAll increments limit counter once per update and probes once per interval when limit exceeded`() =
        runTest {
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = true)

            whenever(renderer.renderAll(eq(context), eq(true))).thenThrow(
                IllegalArgumentException("RemoteViews too large", TransactionTooLargeException()),
            )

            val mockLimitManager: WidgetLimitManager = mock()
            whenever(mockLimitManager.isLimitExceeded()).thenReturn(true)

            var counter = 0
            whenever(mockLimitManager.incrementUpdateCounter()).thenAnswer { counter++ }
            whenever(mockLimitManager.shouldProbe()).thenAnswer {
                counter % WidgetLimitManager.PROBE_INTERVAL_UPDATES == 0
            }
            whenever(mockLimitManager.performProbeUpdate(any(), any())).thenAnswer { invocation ->
                val block = invocation.getArgument<suspend (Context, Boolean) -> UpdateResult>(1)
                runBlocking { block(context, false) }
            }

            val updater =
                WidgetUpdaterImpl(
                    context = context,
                    appSettingsRepository = appSettingsRepository,
                    renderer = renderer,
                    retryManager = retryManager,
                    limitManager = mockLimitManager,
                    errorClassifier = errorClassifier,
                    scope = backgroundScope,
                )

            repeat(WidgetLimitManager.PROBE_INTERVAL_UPDATES) {
                updater.updateAll()
            }
            advanceUntilIdle()

            verify(mockLimitManager, times(WidgetLimitManager.PROBE_INTERVAL_UPDATES)).incrementUpdateCounter()
            verify(mockLimitManager, times(1)).performProbeUpdate(eq(context), any())
            verify(renderer, times(1)).renderAll(eq(context), forceFull = eq(true))
            verify(
                renderer,
                times(WidgetLimitManager.PROBE_INTERVAL_UPDATES),
            ).renderAll(eq(context), forceFull = eq(false))
        }
}
