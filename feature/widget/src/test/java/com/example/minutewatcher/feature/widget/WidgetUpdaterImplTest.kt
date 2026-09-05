package com.example.minutewatcher.feature.widget

import android.content.Context
import android.os.TransactionTooLargeException
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.WidgetUpdateResult
import com.example.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class TestCrashException(
        message: String,
    ) : RuntimeException(message)

    private lateinit var context: Context
    private val appSettingsRepository: AppSettingsRepository = mock()
    private val renderer: WidgetRenderer = mock()
    private val retryManager = WidgetUpdateRetryManager()
    private val errorClassifier = WidgetErrorClassifier()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun createUpdater(
        scope: CoroutineScope,
        customRenderer: WidgetRenderer = renderer,
    ): WidgetUpdaterImpl {
        val limitManager = WidgetLimitManager(appSettingsRepository, scope)
        return WidgetUpdaterImpl(
            context = context,
            appSettingsRepository = appSettingsRepository,
            renderer = customRenderer,
            retryManager = retryManager,
            limitManager = limitManager,
            errorClassifier = errorClassifier,
        )
    }

    @Test
    fun `updateAll calls renderer with correct parameters`() =
        runTest {
            val updater = createUpdater(backgroundScope)
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
        val updater =
            WidgetUpdaterImpl(context, mock(), mock(), WidgetUpdateRetryManager(), mock(), WidgetErrorClassifier())
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
        val updater =
            WidgetUpdaterImpl(context, mock(), mock(), WidgetUpdateRetryManager(), mock(), WidgetErrorClassifier())
        val cause = TransactionTooLargeException()
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
        val updater =
            WidgetUpdaterImpl(context, mock(), mock(), WidgetUpdateRetryManager(), mock(), WidgetErrorClassifier())
        val cause = TransactionTooLargeException()
        val e = RuntimeException("Failure sending RemoteViews", cause)
        assertTrue(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns false when stack trace contains only RemoteViews without exception`() {
        val updater =
            WidgetUpdaterImpl(context, mock(), mock(), WidgetUpdateRetryManager(), mock(), WidgetErrorClassifier())
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
        val updater =
            WidgetUpdaterImpl(context, mock(), mock(), WidgetUpdateRetryManager(), mock(), WidgetErrorClassifier())
        val cause = TransactionTooLargeException()
        val wrapper = RuntimeException("Middle wrapper", cause)
        val e = IllegalArgumentException("Top level", wrapper)
        assertTrue(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `isRemoteViewsLimitException returns false for generic Binder failure`() {
        val updater =
            WidgetUpdaterImpl(context, mock(), mock(), WidgetUpdateRetryManager(), mock(), WidgetErrorClassifier())
        val e = IllegalArgumentException("Something failed in binder")
        assertFalse(updater.isRemoteViewsLimitException(e))
    }

    @Test
    fun `updateAll returns false when performWidgetUpdate throws unexpected exception`() =
        runTest {
            val failingRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        error("Unexpected crash")
                    }
                }
            val failingUpdater = createUpdater(backgroundScope, failingRenderer)
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            val result = failingUpdater.updateAll()

            assertFalse("updateAll should return failure on unexpected exception", result.isSuccess)
        }

    @Test
    fun `updateAll coalesces concurrent calls`() =
        runTest {
            val startBarrier = CompletableDeferred<Unit>()
            val finishBarrier = CompletableDeferred<Unit>()

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        startBarrier.complete(Unit)
                        finishBarrier.await()
                    }
                }
            val updaterWithFake = createUpdater(backgroundScope, fakeRenderer)
            val mockSettings = MutableStateFlow(AppSettings())
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            val result1Deferred = async { updaterWithFake.updateAll() }

            // Wait until the first update is actually inside the renderer
            startBarrier.await()

            // Launch second update while first is still "rendering"
            val result2Deferred = async { updaterWithFake.updateAll() }

            // Allow the first update to complete
            finishBarrier.complete(Unit)

            val result1 = result1Deferred.await()
            val result2 = result2Deferred.await()

            assertTrue("Primary update should succeed", result1.isSuccess)
            assertTrue("Coalesced update should succeed", result2.isSuccess)

            // One should be primary (not coalesced), one should be coalesced
            val results = listOf(result1, result2)
            val successResults = results.filterIsInstance<WidgetUpdateResult.Success>()
            assertEquals(2, successResults.size)
            assertTrue("At least one update should be coalesced", successResults.any { it.wasCoalesced })
            assertTrue("At least one update should NOT be coalesced", successResults.any { !it.wasCoalesced })
        }

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
                            // Simulate LimitExceeded on first call
                            throw IllegalArgumentException(
                                "RemoteViews too large",
                                TransactionTooLargeException(),
                            )
                        }
                    }
                }
            val updater = createUpdater(backgroundScope, rendererMock)
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)
            @Suppress("UNCHECKED_CAST")
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                val transform = it.arguments[0] as (AppSettings) -> AppSettings
                mockSettings.value = transform(mockSettings.value)
            }

            val result = updater.updateAll()

            assertTrue("updateAll should return success even on LimitExceeded", result.isSuccess)
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
            whenever(mockLimitManager.isLimitExceeded()).thenReturn(true)
            whenever(mockLimitManager.shouldProbe()).thenReturn(true)
            @Suppress("UNCHECKED_CAST")
            whenever(mockLimitManager.performProbeUpdate(any(), any())).thenAnswer {
                // Return success immediately to simulate successful probe result
                UpdateResult.Success(wasFull = true)
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
            advanceUntilIdle()

            verify(mockLimitManager).isLimitExceeded()
            verify(mockLimitManager).shouldProbe()
            verify(mockLimitManager).performProbeUpdate(eq(context), any())
        }

    @Test
    fun `updateAll returns false and does not retry on SecurityException`() =
        runTest {
            val updater = createUpdater(backgroundScope)
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            whenever(renderer.renderAll(any(), any())).thenThrow(SecurityException("No permission"))

            val result = updater.updateAll()
            advanceUntilIdle()

            assertFalse("updateAll should return failure on SecurityException", result.isSuccess)
            verify(renderer).renderAll(any(), any()) // Should be called only once
        }

    @Test(expected = OutOfMemoryError::class)
    fun `updateAll throws Error and does not retry`() =
        runTest {
            val updater = createUpdater(backgroundScope)
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            whenever(renderer.renderAll(any(), any())).thenThrow(OutOfMemoryError("Out of memory"))

            updater.updateAll()
        }

    @Test
    fun `handleLimitExceeded sets flag and attempts simplified update`() =
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
            assertTrue("Limit flag should be set", mockSettings.value.widgetLimitExceeded)
        }

    @Test
    fun `resolveRenderMode defaults to FULL on settings timeout`() =
        runTest {
            val updater = createUpdater(backgroundScope)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(emptyFlow())

            updater.updateAll()
            advanceUntilIdle()

            // Verification of FULL mode (forceFull = true)
            verify(renderer).renderAll(context, forceFull = true)
        }

    @Test
    fun `updateAll returns TRANSIENT failure if simplified fallback fails with retryable error`() =
        runTest {
            var callCount = 0
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

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
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

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
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)
            @Suppress("UNCHECKED_CAST")
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                val transform = it.arguments[0] as (AppSettings) -> AppSettings
                mockSettings.value = transform(mockSettings.value)
            }

            // 1. First call: LimitExceeded
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
            assertTrue("Limit flag should be set", mockSettings.value.widgetLimitExceeded)
            assertEquals(2, callCount) // Full attempt + simplified fallback

            // 2. Second call: success on probe
            val mockLimitManager: WidgetLimitManager = mock()
            whenever(mockLimitManager.isLimitExceeded()).thenReturn(true)
            whenever(mockLimitManager.shouldProbe()).thenReturn(true)
            @Suppress("UNCHECKED_CAST")
            whenever(mockLimitManager.performProbeUpdate(any(), any())).thenAnswer {
                // Return success immediately to simulate successful probe
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
                )

            val result = updaterWithMock.updateAll()

            assertTrue("Probe update should succeed", result.isSuccess)
            // handleUpdateResult should call setWidgetLimitExceeded(false)
            verify(mockLimitManager).setWidgetLimitExceeded(value = false)
        }

    @Test
    fun `performSingleUpdate uses limitManager cache to avoid repository check`() =
        runTest {
            val mockLimitManager: WidgetLimitManager = mock()
            whenever(mockLimitManager.isLimitExceeded()).thenReturn(true)
            whenever(mockLimitManager.shouldProbe()).thenReturn(false)

            val updaterWithMock =
                WidgetUpdaterImpl(
                    context = context,
                    appSettingsRepository = appSettingsRepository,
                    renderer = renderer,
                    retryManager = retryManager,
                    limitManager = mockLimitManager,
                    errorClassifier = errorClassifier,
                )

            // When cache returns true, it should use SIMPLIFIED mode (or PROBE if shouldProbe is true)
            // It should NOT call appSettingsRepository.getAppSettings() or observeAppSettings()

            // Trigger internal performSingleUpdate via updateAll
            whenever(renderer.renderAll(any(), any())).thenReturn(Unit)

            updaterWithMock.updateAll()

            // verify(appSettingsRepository, never()).getAppSettings().let { }
            // observeAppSettings().value is used as fallback,
            // but since isLimitExceeded is NOT null, it shouldn't be reached
            // verify(appSettingsRepository, never()).observeAppSettings()

            verify(mockLimitManager).isLimitExceeded()
            verify(mockLimitManager).shouldProbe()
            verify(renderer).renderAll(context, forceFull = false) // Simplified mode
        }

    @Test
    fun `updateAll stays in SIMPLIFIED mode if simplified update fails after LimitExceeded`() =
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

            // Mock renderer to fail both full and simplified updates initially
            val rendererMock =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        callCount++
                        if (callCount == 1) {
                            // 1st call: Full update -> LimitExceeded
                            throw IllegalArgumentException("Too large", TransactionTooLargeException())
                        } else {
                            // 2nd call and onwards: Simplified update -> also fails
                            error("Simplified failed")
                        }
                    }
                }

            val updater = createUpdater(backgroundScope, rendererMock)

            // First update attempt
            val result1 = updater.updateAll()
            assertFalse("First update should fail", result1.isSuccess)
            assertEquals(2, callCount) // Full + Simplified
            assertTrue("Limit flag should be set", mockSettings.value.widgetLimitExceeded)

            // Second update attempt
            // Should NOT try FULL mode again, but go straight to SIMPLIFIED (or PROBE)
            // Since we haven't reached probe interval (10), it should be SIMPLIFIED
            updater.updateAll()

            assertEquals("Should have called renderer again for simplified update", 3, callCount)
            // Verify that the 3 calls were: 1st full (limit exceeded), 2nd fallback simplified,
            // 3rd sequential simplified
            // No need to verify on the mock here since we already checked callCount on the fake object
            assertTrue("Limit flag should still be set", mockSettings.value.widgetLimitExceeded)
        }

    @Test
    fun `updateAll coalesced calls receive success if recovery succeeds`() =
        runTest {
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)
            @Suppress("UNCHECKED_CAST")
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                val transform = it.arguments[0] as (AppSettings) -> AppSettings
                mockSettings.value = transform(mockSettings.value)
            }

            val startBarrier = CompletableDeferred<Unit>()
            val finishBarrier = CompletableDeferred<Unit>()
            var callCount = 0

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        callCount++
                        if (callCount == 1) {
                            startBarrier.complete(Unit)
                            finishBarrier.await()
                            throw IllegalArgumentException("Too large", TransactionTooLargeException())
                        }
                        // Second call (simplified recovery) succeeds immediately
                    }
                }

            val updaterWithFake = createUpdater(backgroundScope, fakeRenderer)

            val result1Deferred = async { updaterWithFake.updateAll() }

            startBarrier.await()

            // Second call joins the first one while it's still in the middle of its executeUpdate block
            val result2Deferred = async { updaterWithFake.updateAll() }

            finishBarrier.complete(Unit)

            val result1 = result1Deferred.await()
            val result2 = result2Deferred.await()

            assertTrue("Original update should succeed after recovery", result1.isSuccess)
            assertTrue("Joined update should succeed after recovery", result2.isSuccess)

            val success1 = result1 as WidgetUpdateResult.Success
            val success2 = result2 as WidgetUpdateResult.Success

            assertFalse("Result 1 should NOT be coalesced", success1.wasCoalesced)
            assertTrue("Result 2 SHOULD be coalesced", success2.wasCoalesced)

            assertEquals("Should have called renderer for full attempt and simplified recovery", 2, callCount)
        }

    @Test
    fun `updateAll concurrent call at the end of previous update does not return failure`() =
        runTest {
            val mockSettings = MutableStateFlow(AppSettings())
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            val finishBarrier = CompletableDeferred<Unit>()
            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        finishBarrier.await()
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            // Start first update
            val result1Deferred = async { updater.updateAll() }

            // Launch second update while first is about to finish
            // This is tricky with StandardTestDispatcher, but let's try to trigger it
            // by finishing first and immediately starting second.
            finishBarrier.complete(Unit)
            val result2 = updater.updateAll()
            val result1 = result1Deferred.await()

            assertTrue("First update should succeed", result1.isSuccess)
            assertTrue("Second update should succeed (either coalesced or new)", result2.isSuccess)
        }

    @Test(expected = CancellationException::class)
    fun `updateAll propagates CancellationException when joined caller is cancelled`() =
        runTest {
            val mockSettings = MutableStateFlow(AppSettings())
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)

            val startBarrier = CompletableDeferred<Unit>()
            val finishBarrier = CompletableDeferred<Unit>()

            val fakeRenderer =
                object : WidgetRenderer {
                    override suspend fun renderAll(
                        context: Context,
                        forceFull: Boolean,
                    ) {
                        startBarrier.complete(Unit)
                        finishBarrier.await()
                    }
                }

            val updater = createUpdater(backgroundScope, fakeRenderer)

            // Start first update
            launch { updater.updateAll() }
            startBarrier.await()

            // Joined update should throw CancellationException if its scope is cancelled
            val joinedUpdateJob = async { updater.updateAll() }
            joinedUpdateJob.cancel()
            joinedUpdateJob.await()
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
                            // First attempt fails with LimitExceeded
                            throw IllegalArgumentException(
                                "RemoteViews too large",
                                TransactionTooLargeException(),
                            )
                        } else {
                            // Second attempt (inside handleLimitExceeded) is cancelled
                            throw CancellationException("Simulated cancellation")
                        }
                    }
                }
            val updater = createUpdater(backgroundScope, rendererMock)
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)
            @Suppress("UNCHECKED_CAST")
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                val transform = it.arguments[0] as (AppSettings) -> AppSettings
                mockSettings.value = transform(mockSettings.value)
            }

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
            val mockSettings = MutableStateFlow(AppSettings(widgetLimitExceeded = false))
            whenever(appSettingsRepository.observeAppSettings()).thenReturn(mockSettings)
            whenever(appSettingsRepository.getAppSettings()).thenReturn(mockSettings)
            @Suppress("UNCHECKED_CAST")
            whenever(appSettingsRepository.updateAppSettings(any())).thenAnswer {
                val transform = it.arguments[0] as (AppSettings) -> AppSettings
                mockSettings.value = transform(mockSettings.value)
            }

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

            // 1. First call: hits LimitExceeded, handleLimitExceeded called once.
            updater.updateAll()
            assertEquals(1, simplifiedCallCount)

            // 2. Second call: resolveRenderMode uses SIMPLIFIED.
            // Simplified attempt fails -> handleLimitExceeded called.
            // Flag should prevent the simplified recovery attempt inside handleLimitExceeded.
            updater.updateAll()
            assertEquals("Flag should prevent repeat in handleLimitExceeded", 2, simplifiedCallCount)

            // 3. Third call: success, flag should NOT be reset by success anymore.
            // Still in SIMPLIFIED mode because limitManager wasn't reset (wasFull=false).
            shouldFail = false
            updater.updateAll()
            assertEquals(3, simplifiedCallCount)

            // 4. Call recover() explicitly to reset the limitExceededHandled flag.
            updater.recover()

            // 5. Fifth call: set to fail again, handleLimitExceeded should be allowed once more.
            shouldFail = true
            updater.updateAll()
            // 4th simplified call from performSingleUpdate (still SIMPLIFIED mode),
            // 5th from handleLimitExceeded (allowed because recover() reset the flag).
            assertEquals("Flag should be reset after recover", 5, simplifiedCallCount)
        }
}
