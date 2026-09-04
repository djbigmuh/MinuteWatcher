package com.example.minutewatcher.core.model

/**
 * Состояние настроек виджета.
 * Содержит общие настройки (layout, transparency, fontSize)
 * и SIM-специфичные настройки (simStates).
 *
 * @property layoutMode Режим компоновки виджета.
 * @property simArrangement Расположение SIM (горизонтальное/вертикальное).
 * @property transparency Прозрачность фона (0-100).
 * @property fontSize Размер шрифта (0-4).
 * @property backgroundColor Цвет фона.
 * @property notifyLowMinutes Уведомление о низком остатке минут.
 * @property notifyLowTraffic Уведомление о низком остатке трафика.
 * @property simStates SIM-специфичные настройки (ключ: индекс слота).
 * @property widgetLimitExceeded Флаг превышения лимита виджета.
 * @property settingsLoadFailed Флаг ошибки загрузки настроек.
 * @property simListLoadFailed Флаг ошибки загрузки списка SIM.
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
         * Валидирует настройки перед сохранением.
         *
         * Строгое соблюдение API: значения coerceIn гарантируют, что виджет
         * получит корректные параметры даже если UI передал некорректные данные.
         *
         * @param state Исходное состояние.
         * @return Валидированное состояние.
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
                    // Сбрасываем флаг превышения лимита при сохранении настроек пользователем.
                    // Это позволяет вручную "вылечить" виджет, если пользователь изменил состав данных.
                    widgetLimitExceeded = false,
                )
            return if (validated == state) state else validated
        }
    }
}

/**
 * Настройки одной SIM-карты в виджете.
 *
 * @property showSim Показывать SIM-карту.
 * @property showIcon Показывать иконку.
 * @property showName Показывать имя.
 * @property showMinutes Показывать минуты.
 * @property showMinutesLimit Показывать лимит минут.
 * @property showTraffic Показывать трафик.
 * @property showTrafficLimit Показывать лимит трафика.
 */
data class SimSettingsState(
    val showSim: Boolean = true,
    val showIcon: Boolean = true,
    val showName: Boolean = true,
    val showMinutes: Boolean = true,
    val showMinutesLimit: Boolean = false,
    val showTraffic: Boolean = true,
    val showTrafficLimit: Boolean = false,
)
