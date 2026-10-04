package com.example.minutewatcher.feature.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// UI-infra SDK pin: Espresso InputManager reflection fails on SDK > 35 in Robolectric
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class MainScreenScrollTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockViewModel: MainViewModel = mock()

    @Test
    fun `scrolls to highlighted slot once and ignores subsequent updates`() {
        val uiState = mutableStateOf<SimCardListState>(SimCardListState.Loading)
        val highlightedSlot = mutableStateOf<Int?>(5)

        val simCards =
            (0..10).map { slot ->
                createMockSimCard(slot)
            }

        val actions =
            SimCardScreenActions(
                onOpenSettings = {},
                onAddWidget = {},
                onThemeChange = {},
                onLanguageChange = {},
                onGrantPhonePermission = {},
                onShowUsageAccessDialog = {},
                onOpenUsageAccessSettings = {},
                onOpenAppSettings = {},
                onGrantNotificationPermission = {},
            )

        var listState: LazyListState? = null

        composeTestRule.setContent {
            val state = rememberLazyListState()
            listState = state
            MainScreenContent(
                uiState = uiState.value,
                paddingValues = PaddingValues(0.dp),
                viewModel = mockViewModel,
                trackingBannerState = TrackingBannerState(),
                highlightedSlot = highlightedSlot.value,
                actions = actions,
                listState = state,
            )
        }

        // 1. Initially loading - Operator 5 does not exist
        composeTestRule.onNodeWithText("Operator 5").assertDoesNotExist()

        // 2. Data arrives with slot 5
        uiState.value = SimCardListState.Success(simCards)
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(conditionDescription = "Wait for scroll to index 5", timeoutMillis = 5000L) {
            listState?.firstVisibleItemIndex == 5
        }

        assertEquals(5, listState?.firstVisibleItemIndex)
        composeTestRule.onNodeWithText("Operator 0").assertIsNotDisplayed()
        composeTestRule.onNodeWithText("Operator 5").assertIsDisplayed()

        // 3. Subsequent update with new data
        val updatedSimCards = simCards.map { if (it.slot == 5) it.copy(minutesUsed = 42) else it }
        uiState.value = SimCardListState.Success(updatedSimCards)
        composeTestRule.waitForIdle()

        // Position must remain at index 5 and not scroll back or elsewhere
        assertEquals(5, listState?.firstVisibleItemIndex)
        composeTestRule.onNodeWithText("Operator 5").assertIsDisplayed()
        composeTestRule.onNodeWithText("Operator 0").assertIsNotDisplayed()

        // Verify updated data reached the UI
        composeTestRule.onNodeWithText("42", substring = true).assertIsDisplayed()
    }

    @Test
    fun `does not scroll if highlighted slot is missing in data`() {
        val uiState = mutableStateOf<SimCardListState>(SimCardListState.Loading)
        val highlightedSlot = mutableStateOf<Int?>(20)

        val simCards = (0..5).map { slot -> createMockSimCard(slot) }

        val actions =
            SimCardScreenActions(
                onOpenSettings = {},
                onAddWidget = {},
                onThemeChange = {},
                onLanguageChange = {},
                onGrantPhonePermission = {},
                onShowUsageAccessDialog = {},
                onOpenUsageAccessSettings = {},
                onOpenAppSettings = {},
                onGrantNotificationPermission = {},
            )

        var listState: LazyListState? = null

        composeTestRule.setContent {
            val state = rememberLazyListState()
            listState = state
            MainScreenContent(
                uiState = uiState.value,
                paddingValues = PaddingValues(0.dp),
                viewModel = mockViewModel,
                trackingBannerState = TrackingBannerState(),
                highlightedSlot = highlightedSlot.value,
                actions = actions,
                listState = state,
            )
        }

        uiState.value = SimCardListState.Success(simCards)
        composeTestRule.waitForIdle()

        // List should remain at initial scroll position 0
        assertEquals(0, listState?.firstVisibleItemIndex)
        composeTestRule.onNodeWithText("Operator 0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Operator 5").assertIsNotDisplayed()
    }

    private fun createMockSimCard(slot: Int) =
        SimCardUiModel(
            slot = slot,
            identity = "sim_$slot",
            operatorName = "Operator $slot",
            iconRes = R.drawable.ic_unknown,
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
