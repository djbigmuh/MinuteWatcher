package host.serv.djbigmuh.minutewatcher.feature.widget

import android.os.TransactionTooLargeException
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdateResult
import host.serv.djbigmuh.minutewatcher.core.model.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdaterErrorClassificationTest : BaseWidgetUpdaterFixture() {
    private val defaultScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)

    @Test
    fun `isRemoteViewsLimitException returns false for generic IllegalArgumentException`() {
        val updater =
            WidgetUpdaterImpl(
                context,
                mock(),
                mock(),
                WidgetUpdateRetryManager(),
                mock(),
                WidgetErrorClassifier(),
                defaultScope,
            )
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
            WidgetUpdaterImpl(
                context,
                mock(),
                mock(),
                WidgetUpdateRetryManager(),
                mock(),
                WidgetErrorClassifier(),
                defaultScope,
            )
        val e =
            IllegalArgumentException(
                "RemoteViews too large",
                TransactionTooLargeException(),
            )
        assertTrue(
            updater.isRemoteViewsLimitException(e),
        )
    }

    @Test
    fun `isRemoteViewsLimitException returns true for RuntimeException with TransactionTooLargeException cause`() {
        val updater =
            WidgetUpdaterImpl(
                context,
                mock(),
                mock(),
                WidgetUpdateRetryManager(),
                mock(),
                WidgetErrorClassifier(),
                defaultScope,
            )
        val cause = TransactionTooLargeException()
        val e = RuntimeException("RemoteViews failed", cause)
        assertTrue(
            updater.isRemoteViewsLimitException(e),
        )
    }

    @Test
    fun `isRemoteViewsLimitException returns false when stack trace contains only RemoteViews without exception`() {
        val updater =
            WidgetUpdaterImpl(
                context,
                mock(),
                mock(),
                WidgetUpdateRetryManager(),
                mock(),
                WidgetErrorClassifier(),
                defaultScope,
            )
        val e = IllegalArgumentException("Some other error")
        assertFalse(
            updater.isRemoteViewsLimitException(e),
        )
    }

    @Test
    fun `isRemoteViewsLimitException returns true for deeply nested TransactionTooLargeException cause`() {
        val updater =
            WidgetUpdaterImpl(
                context,
                mock(),
                mock(),
                WidgetUpdateRetryManager(),
                mock(),
                WidgetErrorClassifier(),
                defaultScope,
            )
        val root = TransactionTooLargeException()
        val nested = RuntimeException("Nested", root)
        val e = IllegalArgumentException("RemoteViews error", nested)
        assertTrue(
            updater.isRemoteViewsLimitException(e),
        )
    }

    @Test
    fun `isRemoteViewsLimitException returns false for generic Binder failure`() {
        val updater =
            WidgetUpdaterImpl(
                context,
                mock(),
                mock(),
                WidgetUpdateRetryManager(),
                mock(),
                WidgetErrorClassifier(),
                defaultScope,
            )
        val e = RuntimeException("Binder transaction failed")
        assertFalse(
            updater.isRemoteViewsLimitException(e),
        )
    }

    @Test
    fun `updateAll returns false when performWidgetUpdate throws unexpected exception`() =
        runTest {
            val updater = createUpdater(backgroundScope)
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            whenever(renderer.renderAll(any(), any())).thenThrow(TestCrashException("Unexpected error"))

            val result = updater.updateAll()
            advanceUntilIdle()

            assertFalse("updateAll should return failure on unexpected exception", result.isSuccess)
        }

    @Test
    fun `updateAll returns false and does not retry on SecurityException`() =
        runTest {
            val updater = createUpdater(backgroundScope)
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            whenever(renderer.renderAll(any(), any())).thenThrow(SecurityException("No permission"))

            val result = updater.updateAll()
            advanceUntilIdle()

            assertFalse("updateAll should return failure on SecurityException", result.isSuccess)
            verify(renderer).renderAll(any(), any())
        }

    @Test
    fun `renderer throwing IllegalStateException with TransactionTooLargeException triggers limit exceeded`() =
        runTest {
            whenever(renderer.renderAll(any(), any()))
                .thenThrow(IllegalStateException("Binder", TransactionTooLargeException()))
                .thenReturn(Unit)
            val updater = createUpdater(backgroundScope, renderer)
            appSettingsRepository.settingsFlow.value = AppSettings(widgetLimitExceeded = false)

            val result =
                withTimeout(5.seconds) {
                    updater.updateAll()
                }

            assertTrue("Update should succeed on retry in simplified mode", result is WidgetUpdateResult.Success)
            assertTrue(
                "Widget limit exceeded flag should be set",
                appSettingsRepository.settingsFlow.value.widgetLimitExceeded,
            )
            verify(renderer, times(2)).renderAll(any(), any())
        }
}
