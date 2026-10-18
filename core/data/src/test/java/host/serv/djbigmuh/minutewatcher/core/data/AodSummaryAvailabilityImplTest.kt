package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AodSummaryAvailabilityImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val notificationHelper: NotificationHelper = mock()

    @Test
    fun `isAodSummaryAvailable delegates to NotificationHelper on ioDispatcher`() =
        runTest {
            whenever(notificationHelper.isAodSummaryAvailable()).thenReturn(true)

            val availability =
                AodSummaryAvailabilityImpl(
                    notificationHelper = notificationHelper,
                    ioDispatcher = mainDispatcherRule.testDispatcher,
                )

            assertTrue(availability.isAodSummaryAvailable())
        }

    @Test
    fun `isAodSummaryAvailable returns false when helper returns false`() =
        runTest {
            whenever(notificationHelper.isAodSummaryAvailable()).thenReturn(false)

            val availability =
                AodSummaryAvailabilityImpl(
                    notificationHelper = notificationHelper,
                    ioDispatcher = mainDispatcherRule.testDispatcher,
                )

            assertFalse(availability.isAodSummaryAvailable())
        }
}
