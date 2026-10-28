package host.serv.djbigmuh.minutewatcher.feature.main

import host.serv.djbigmuh.minutewatcher.core.domain.SeedUnavailableException
import host.serv.djbigmuh.minutewatcher.core.domain.SimNotFoundException
import host.serv.djbigmuh.minutewatcher.core.resources.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelSimDataTest : MainViewModelTestFixtures() {
    @Test
    fun `executeSimOperation sets OperationError on SeedUnavailableException`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)
            val slot = 1
            whenever(simIdentityRepository.getIdentity(slot)).thenThrow(SeedUnavailableException(slot))

            viewModel.updateLimits(slot, 100, 1000)
            advanceUntilIdle()

            val error = viewModel.lastOperationError.value
            assertTrue("Expected OperationError but was $error", error != null)
            assertEquals(slot, error?.slot)
            assertEquals(R.string.error_storage_unavailable, error?.messageRes)
            assertTrue("Args should contain slot", error?.args?.contains(slot) == true)
        }

    @Test
    fun `executeSimOperation sets OperationError on SimNotFoundException`() =
        testScope.runTest {
            val viewModel = createViewModel(backgroundScope)
            val slot = 0
            whenever(simIdentityRepository.getIdentity(slot)).thenThrow(SimNotFoundException(slot))

            viewModel.updateLimits(slot, 100, 1000)
            advanceUntilIdle()

            val error = viewModel.lastOperationError.value
            assertTrue("Expected OperationError but was $error", error != null)
            assertEquals(slot, error?.slot)
            assertEquals(R.string.error_sim_removed, error?.messageRes)
            assertTrue("Args should contain slot", error?.args?.contains(slot) == true)
        }
}
