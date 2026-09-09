package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.domain.SeedUnavailableException
import com.example.minutewatcher.core.domain.SimNotFoundException
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
