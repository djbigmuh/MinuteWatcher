package com.example.minutewatcher.core.model

/**
 * Widget settings state.
 * Contains general settings (layout, transparency, fontSize)
 * and SIM-specific settings (simStates).
 *
 * @property layoutMode Widget layout mode.
 * @property simArrangement SIM arrangement (horizontal/vertical).
 * @property transparency Background transparency (0-100).
 * @property fontSize Font size (0-4).
 * @property backgroundColor Background color.
 * @property notifyLowMinutes Low minutes notification.
 * @property notifyLowTraffic Low traffic notification.
 * @property simStates SIM-specific settings (key: slot index).
 * @property widgetLimitExceeded Widget limit exceeded flag.
 * @property settingsLoadFailed General settings load failure flag.
 * @property simListLoadFailed SIM list load failure flag.
 */
data class WidgetSettingsState(
    val layoutMode: Int = 2,
    val simArrangement: Int = 0,
    val transparency: Int = 50,
    val fontSize: Int = 2,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val notifyLowMinutes: Boolean = true,
    val notifyLowTraffic: Boolean = true,
    val simStates: Map<Int, SimSettingsState> = emptyMap(),
    val widgetLimitExceeded: Boolean = false,
    /**
     * Whether the general app settings (layout, transparency, etc.) failed to load.
     * If this is true, the entire widget settings UI should typically show an error.
     */
    val settingsLoadFailed: Boolean = false,
    /**
     * Whether the active SIM list or their specific settings failed to load due to a timeout.
     * General settings can still be valid and savable if this is true.
     */
    val simListLoadFailed: Boolean = false,
) {
    companion object {
        /**
         * Validates settings before saving.
         *
         * Strict API compliance: coerceIn values guarantee that the widget
         * receives valid parameters even if the UI passed invalid data.
         *
         * @param state Initial state.
         * @return Validated state.
         */
        fun validate(state: WidgetSettingsState): WidgetSettingsState {
            val validated =
                state.copy(
                    layoutMode =
                        state.layoutMode.coerceIn(
                            Constants.Widget.MIN_LAYOUT_MODE,
                            Constants.Widget.MAX_LAYOUT_MODE,
                        ),
                    simArrangement =
                        state.simArrangement.coerceIn(
                            Constants.Widget.MIN_SIM_ARRANGEMENT,
                            Constants.Widget.MAX_SIM_ARRANGEMENT,
                        ),
                    transparency =
                        state.transparency.coerceIn(
                            Constants.Widget.MIN_TRANSPARENCY,
                            Constants.Widget.MAX_TRANSPARENCY,
                        ),
                    fontSize =
                        state.fontSize.coerceIn(
                            Constants.Widget.MIN_FONT_SIZE,
                            Constants.Widget.MAX_FONT_SIZE,
                        ),
                    backgroundColor = state.backgroundColor,
                    widgetLimitExceeded = state.widgetLimitExceeded,
                )
            return if (validated == state) state else validated
        }
    }
}

/**
 * Settings for a single SIM card in the widget.
 *
 * @property showSim Show SIM card.
 * @property showIcon Show icon.
 * @property showName Show name.
 * @property showMinutes Show minutes.
 * @property showMinutesLimit Show minutes limit.
 * @property showTraffic Show traffic.
 * @property showTrafficLimit Show traffic limit.
 */
data class SimSettingsState(
    val showSim: Boolean = true,
    val showIcon: Boolean = true,
    val showName: Boolean = true,
    val showMinutes: Boolean = true,
    val showMinutesLimit: Boolean = false,
    val showTraffic: Boolean = true,
    val showTrafficLimit: Boolean = false,
    val lastKnownIdentity: String? = null,
)
