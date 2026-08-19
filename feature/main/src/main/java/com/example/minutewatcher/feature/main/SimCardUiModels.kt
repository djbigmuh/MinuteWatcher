package com.example.minutewatcher.feature.main

import java.io.Serializable

/**
 * Данные диалога установки лимитов.
 * Serializable для сохранения в SavedStateHandle.
 *
 * @property slot Индекс слота SIM.
 * @property name Имя оператора.
 * @property minutes Текущий лимит минут.
 * @property traffic Текущий лимит трафика (МБ).
 * @property rolloverEnabled Включён ли перенос остатков.
 */
data class LimitDialogData(
    val slot: Int,
    val name: String,
    val minutes: Int,
    val traffic: Int,
    val rolloverEnabled: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Данные диалога выбора дня автосброса.
 * Serializable для сохранения в SavedStateHandle.
 *
 * @property slot Индекс слота SIM.
 * @property day Текущий день автосброса.
 */
data class DayDialogData(
    val slot: Int,
    val day: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Данные диалога корректировки остатка.
 * Serializable для сохранения в SavedStateHandle.
 *
 * @property slot Индекс слота SIM.
 * @property currentValue Текущее значение остатка.
 * @property isMinutes true если корректировка минут, false если трафика.
 */
data class AdjustDialogData(
    val slot: Int,
    val currentValue: Float,
    val isMinutes: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Опция языка для LanguageMenu.
 *
 * @property code Код языка (null для системного).
 * @property name Название языка.
 * @property flag Флаг/эмодзи языка.
 */
data class LanguageOption(
    val code: String?,
    val name: String,
    val flag: String,
)
