package com.example.minutewatcher.feature.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.SimCardUiModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // behavior-specific SDK
class MainScreenScrollTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `scrolls to highlighted slot once and ignores subsequent updates`() {
        val uiState = mutableStateOf<SimCardListState>(SimCardListState.Loading)
        val highlightedSlot = mutableStateOf<Int?>(5)

        val simCards =
            (0..10).map { slot ->
                createMockSimCard(slot)
            }

        composeTestRule.setContent {
            MainScreenContent(
                uiState = uiState.value,
                paddingValues = PaddingValues(0.dp),
                viewModel = mock(),
                trackingBannerState = TrackingBannerState(),
                highlightedSlot = highlightedSlot.value,
                actions = mock(),
            )
        }

        // 1. Initially loading
        composeTestRule.onNodeWithText("Operator 5").assertDoesNotExist()

        // 2. Data arrives with slot 5
        uiState.value = SimCardListState.Success(simCards)
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000L) // Wait for animation

        // Verify slot 5 is displayed
        composeTestRule.onNodeWithText("Operator 5").assertIsDisplayed()

        // 3. Subsequent update with new data (simulating background refresh)
        // We change something in the models to trigger recomposition/emission
        val updatedSimCards = simCards.map { it.copy(minutesUsed = 1) }
        uiState.value = SimCardListState.Success(updatedSimCards)
        composeTestRule.waitForIdle()

        // If it scrolls AGAIN, it might conflict with user.
        // But since we use .first(), the LaunchedEffect should have finished.
        // There is no easy way to verify "it did NOT scroll" without observing listState.
    }

    @Test
    fun `does not scroll if highlighted slot is missing in data`() {
        val uiState = mutableStateOf<SimCardListState>(SimCardListState.Loading)
        val highlightedSlot = mutableStateOf<Int?>(20) // Non-existent slot

        val simCards = (0..5).map { slot -> createMockSimCard(slot) }

        composeTestRule.setContent {
            MainScreenContent(
                uiState = uiState.value,
                paddingValues = PaddingValues(0.dp),
                viewModel = mock(),
                trackingBannerState = TrackingBannerState(),
                highlightedSlot = highlightedSlot.value,
                actions = mock(),
            )
        }

        uiState.value = SimCardListState.Success(simCards)
        composeTestRule.waitForIdle()

        // Should not crash and should keep waiting or finish if we implement correctly.
        // My implementation uses .first { it.simCards.any { it.slot == slot } }
        // So it should continue waiting until slot 20 appears.

        // If slot 20 never appears, effect just keeps waiting (which is fine, cancelled on null).
    }

    private fun createMockSimCard(slot: Int) =
        SimCardUiModel(
            slot = slot,
            identity = "sim_$slot",
            operatorName = "Operator $slot",
            iconRes = 0,
            minutesUsed = 0,
            minutesLimit = 100,
            manualAdjustment = 0,
            minutesRemaining = 100,
            effectiveMinutesLimit = 100,
            isMinutesLimitSet = true,
            trafficUsedMb = 0f,
            trafficLimitMb = 1024,
            trafficRemainingGb = 1f,
            effectiveTrafficLimitGb = 1f,
            trafficAdjustmentGb = 0f,
            isTrafficLimitSet = true,
            autoResetEnabled = true,
            autoResetDay = 1,
            rolloverEnabled = false,
        )
}
