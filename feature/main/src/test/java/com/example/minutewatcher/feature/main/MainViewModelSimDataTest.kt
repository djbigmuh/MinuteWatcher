package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.domain.SeedUnavailableException
import com.example.minutewatcher.core.domain.SimNotFoundException
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
            val message = error?.message ?: ""
            assertTrue(
                "Message should mention storage unavailable: $message",
                message.contains("Storage unavailable"),
            )
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
            val message = error?.message ?: ""
            assertTrue(
                "Message should mention SIM removed: $message",
                message.contains("SIM was removed"),
            )
        }
}
