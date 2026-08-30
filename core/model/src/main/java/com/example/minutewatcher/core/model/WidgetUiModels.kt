package com.example.minutewatcher.core.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * Состояние виджета для рендера.
 * Содержит все данные, необходимые для рендера виджета.
 *
 * @property layoutMode Режим компоновки виджета.
 * @property simArrangement Расположение SIM.
 * @property backgroundColor Цвет фона.
 * @property transparency Прозрачность фона.
 * @property simUiStates Список состояний SIM-карт.
 * @property isHorizontal Горизонтальное расположение.
 * @property showSeparator Показывать разделитель.
 * @property useSystemColors Использовать системные цвета.
 * @property settingsLoadFailed Флаг ошибки загрузки настроек.
 * @property refreshFailed Флаг ошибки обновления данных.
 * @property isSnapshotEmpty Флаг пустого snapshot.
 */
@Immutable
data class WidgetUiState(
    val layoutMode: Int,
    val simArrangement: Int,
    val backgroundColor: Int,
    val transparency: Int,
    val simUiStates: List<SimUiState>,
    val isHorizontal: Boolean = true,
    val showSeparator: Boolean = false,
    val useSystemColors: Boolean = false,
    val settingsLoadFailed: Boolean = false,
    val simListLoadFailed: Boolean = false,
    val refreshFailed: Boolean = false,
    val isSnapshotEmpty: Boolean = false,
)

/**
 * Строки виджета.
 * Создаются через WidgetStringsFactory.create(context).
 * Пересоздаются при изменении конфигурации (локаль).
 *
 * @property usedText Текст "Использовано".
 * @property minutesAbbr Аббревиатура минут ("мин.").
 * @property limitText Текст "Лимит".
 * @property gbAbbr Аббревиатура гигабайт ("ГБ").
 * @property fractionFormat Формат дроби ("/%1$s").
 */
@Immutable
data class WidgetStrings(
    val usedText: String,
    val minutesAbbr: String,
    val limitText: String,
    val gbAbbr: String,
    val fractionFormat: String,
)

/**
 * Состояние одной SIM-карты для рендера в виджете.
 *
 * @property slot Индекс слота SIM.
 * @property operatorName Имя оператора.
 * @property operatorIconRes Ресурс иконки оператора.
 * @property showSim Показывать SIM-карту.
 * @property showIcon Показывать иконку.
 * @property showName Показывать имя.
 * @property showMinutes Показывать минуты.
 * @property minutesText Текст минут.
 * @property minutesColor Цвет минут.
 * @property showMinutesLimit Показывать лимит минут.
 * @property minutesLimitText Текст лимита минут.
 * @property showTraffic Показывать трафик.
 * @property trafficText Текст трафика.
 * @property trafficColor Цвет трафика.
 * @property showTrafficLimit Показывать лимит трафика.
 * @property trafficLimitText Текст лимита трафика.
 * @property fontMultiplier Множитель размера шрифта.
 * @property textColor Цвет текста.
 * @property secondaryTextColor Вторичный цвет текста.
 * @property minutesUsedFullText Полный текст использованных минут.
 * @property minutesLimitFullText Полный текст лимита минут.
 * @property trafficUsedFullText Полный текст использованного трафика.
 * @property trafficLimitFullText Полный текст лимита трафика.
 * @property layoutSpec Спецификация layout для SimLayoutRenderV2.
 */
@Immutable
data class SimUiState(
    val slot: Int,
    val operatorName: String,
    val operatorIconRes: Int,
    val showSim: Boolean,
    val showIcon: Boolean,
    val showName: Boolean,
    val showMinutes: Boolean,
    val minutesText: String,
    val minutesColor: Int,
    val showMinutesLimit: Boolean,
    val minutesLimitText: String,
    val showTraffic: Boolean,
    val trafficText: String,
    val trafficColor: Int,
    val showTrafficLimit: Boolean,
    val trafficLimitText: String,
    val fontMultiplier: Float,
    val textColor: Int,
    val secondaryTextColor: Int,
    // Detailed fields for SIX_LINES mode
    val minutesUsedFullText: String = "",
    val minutesLimitFullText: String = "",
    val trafficUsedFullText: String = "",
    val trafficLimitFullText: String = "",
    val layoutSpec: SimLayoutSpec = SimLayoutSpec(emptyList()),
)

/**
 * Спецификация layout для SimLayoutRenderV2.
 * Содержит список строк (rows), каждая из которых содержит компоненты.
 *
 * @property rows Список строк layout.
 */
@Immutable
data class SimLayoutSpec(
    val rows: List<SimLayoutRow>,
)

/**
 * Строка layout для SimLayoutRenderV2.
 * Содержит список компонентов (components).
 *
 * @property components Список компонентов строки.
 */
@Immutable
data class SimLayoutRow(
    val components: List<SimLayoutComponent>,
)

/**
 * Компонент layout для SimLayoutRenderV2.
 * Sealed class гарантирует исчерпывающую обработку всех вариантов.
 *
 * Варианты:
 * - [Icon] — иконка (iconRes, sizeDp).
 * - [Text] — текст (text, color, fontSizeSp, isBold).
 * - [Spacer] — spacer (widthDp, heightDp).
 */
@Stable
sealed class SimLayoutComponent {
    @Immutable
    data class Icon(
        val iconRes: Int,
        val sizeDp: Int,
    ) : SimLayoutComponent()

    @Immutable
    data class Text(
        val text: String,
        val color: Int,
        val fontSizeSp: Float,
        val isBold: Boolean = false,
    ) : SimLayoutComponent()

    @Immutable
    data class Spacer(
        val widthDp: Int = 0,
        val heightDp: Int = 0,
    ) : SimLayoutComponent()
}
