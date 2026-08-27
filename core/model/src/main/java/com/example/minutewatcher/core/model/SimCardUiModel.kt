package com.example.minutewatcher.core.model

import androidx.compose.runtime.Immutable

/**
 * Модель SIM-карты для UI.
 * Содержит все данные, необходимые для отображения SIM-карты в UI.
 *
 * @property slot Индекс слота SIM.
 * @property identity Стабильная identity SIM.
 * @property operatorName Имя оператора.
 * @property iconRes Ресурс иконки оператора.
 * @property minutesUsed Использованные минуты.
 * @property minutesLimit Лимит минут.
 * @property manualAdjustment Ручная корректировка минут.
 * @property minutesRemaining Остаток минут.
 * @property effectiveMinutesLimit Эффективный лимит минут.
 * @property isMinutesLimitSet Установлен ли лимит минут.
 * @property trafficUsedMb Использованный трафик (МБ).
 * @property trafficLimitMb Лимит трафика (МБ).
 * @property trafficRemainingGb Остаток трафика (ГБ).
 * @property effectiveTrafficLimitGb Эффективный лимит трафика (ГБ).
 * @property trafficAdjustmentGb Корректировка трафика (ГБ).
 * @property isTrafficLimitSet Установлен ли лимит трафика.
 * @property autoResetEnabled Включён ли автосброс.
 * @property autoResetDay День автосброса.
 * @property rolloverEnabled Включён ли перенос остатков.
 */
@Immutable
data class SimCardUiModel(
    val slot: Int,
    val identity: String,
    val operatorName: String,
    val iconRes: Int,
    val minutesUsed: Int,
    val minutesLimit: Int,
    val manualAdjustment: Int,
    val minutesRemaining: Int,
    val effectiveMinutesLimit: Int,
    val isMinutesLimitSet: Boolean,
    val trafficUsedMb: Float,
    val trafficLimitMb: Int,
    val trafficRemainingGb: Float,
    val effectiveTrafficLimitGb: Float,
    val trafficAdjustmentGb: Float,
    val isTrafficLimitSet: Boolean,
    val autoResetEnabled: Boolean,
    val autoResetDay: Int,
    val rolloverEnabled: Boolean,
)
