package com.example.minutewatcher.core.android.util

import com.example.minutewatcher.core.android.testing.SingletonResetRule
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.WidgetSettingsState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetUiSimVisibilityMapperTest {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

    @Test
    fun `show SIM logic when config missing`() {
        val snapshot =
            GlobalSnapshot(
                version = SchemaVersion(1, 0, 0),
                timestamp = System.currentTimeMillis(),
                simSnapshots =
                    listOf(
                        SimSnapshot(0, "sim_0", "Operator", emptyMap(), 1),
                    ),
                capabilities = emptyList(),
            )

        // Scenario 1: Config missing, load succeeded -> showSim should be false
        val settingsNormal = WidgetSettingsState(simStates = emptyMap(), simListLoadFailed = false)
        val uiStateNormal =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settingsNormal,
                operatorNames = emptyMap(),
            )
        assertFalse(
            "showSim should be false when config missing and load succeeded",
            uiStateNormal.simUiStates[0].showSim,
        )

        // Scenario 2: Config missing, load failed -> showSim should be true (fallback)
        val settingsFailed = WidgetSettingsState(simStates = emptyMap(), simListLoadFailed = true)
        val uiStateFailed =
            WidgetUiMapper.mapFromSnapshot(
                strings = widgetStrings,
                snapshot = snapshot,
                state = settingsFailed,
                operatorNames = emptyMap(),
            )
        assertTrue("showSim should be true when config missing and load failed", uiStateFailed.simUiStates[0].showSim)
    }
}
