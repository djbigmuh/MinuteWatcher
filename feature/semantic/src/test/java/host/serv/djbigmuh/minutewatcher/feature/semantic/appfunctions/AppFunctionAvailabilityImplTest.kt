package host.serv.djbigmuh.minutewatcher.feature.semantic.appfunctions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppFunctionAvailabilityImplTest {
    private class FakeGateway : AppFunctionStateGateway {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var exceptionToThrow: Exception? = null

        override suspend fun setAppFunctionEnabled(
            functionId: String,
            enabled: Boolean,
        ) {
            exceptionToThrow?.let { throw it }
            calls.add(functionId to enabled)
        }
    }

    private lateinit var fakeGateway: FakeGateway
    private lateinit var availability: AppFunctionAvailabilityImpl

    @Before
    fun setup() {
        fakeGateway = FakeGateway()
        availability = AppFunctionAvailabilityImpl(fakeGateway)
    }

    @Test
    @Config(sdk = [35]) // behavior-specific SDK
    fun `setAssistantAccessEnabled below api 36 is no op and does not invoke gateway`() =
        runTest {
            availability.setAssistantAccessEnabled(true)
            assertTrue(fakeGateway.calls.isEmpty())
        }

    @Test
    @Config(sdk = [36]) // behavior-specific SDK
    fun `setAssistantAccessEnabled true on api 36 invokes gateway with correct parameters`() =
        runTest {
            availability.setAssistantAccessEnabled(true)
            assertEquals(1, fakeGateway.calls.size)
            assertEquals(MinuteWatcherAppFunctionService.FUNCTION_ID_GET_USAGE_SUMMARY to true, fakeGateway.calls[0])
        }

    @Test
    @Config(sdk = [36]) // behavior-specific SDK
    fun `setAssistantAccessEnabled false on api 36 invokes gateway with correct parameters`() =
        runTest {
            availability.setAssistantAccessEnabled(false)
            assertEquals(1, fakeGateway.calls.size)
            assertEquals(MinuteWatcherAppFunctionService.FUNCTION_ID_GET_USAGE_SUMMARY to false, fakeGateway.calls[0])
        }

    @Test
    @Config(sdk = [36]) // behavior-specific SDK
    fun `setAssistantAccessEnabled swallows general exceptions from gateway`() =
        runTest {
            fakeGateway.exceptionToThrow = RuntimeException("Gateway error")
            availability.setAssistantAccessEnabled(true)
            assertTrue(fakeGateway.calls.isEmpty())
        }

    @Test(expected = CancellationException::class)
    @Config(sdk = [36]) // behavior-specific SDK
    fun `setAssistantAccessEnabled rethrows CancellationException from gateway`() =
        runTest {
            fakeGateway.exceptionToThrow = CancellationException("Canceled")
            availability.setAssistantAccessEnabled(true)
        }
}
