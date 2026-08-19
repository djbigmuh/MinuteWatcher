package com.example.minutewatcher.feature.main

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Данные диалога установки лимитов.
 * Parcelable для сохранения в SavedStateHandle.
 *
 * @property slot Индекс слота SIM.
 * @property name Имя оператора.
 * @property minutes Текущий лимит минут.
 * @property traffic Текущий лимит трафика (МБ).
 * @property rolloverEnabled Включён ли перенос остатков.
 */
@Parcelize
// Using Parcelable instead of Serializable for Android IPC/Bundle transport.
// Serializable is fragile: schema changes break deserialization silently,
// and it's ~10x slower. Parcelable is the strict Android Best Practice.
data class LimitDialogData(
    val slot: Int,
    val name: String,
    val minutes: Int,
    val traffic: Int,
    val rolloverEnabled: Boolean = false,
) : Parcelable

/**
 * Данные диалога выбора дня автосброса.
 * Parcelable для сохранения в SavedStateHandle.
 *
 * @property slot Индекс слота SIM.
 * @property day Текущий день автосброса.
 */
@Parcelize
// Using Parcelable instead of Serializable for Android IPC/Bundle transport.
// Serializable is fragile: schema changes break deserialization silently,
// and it's ~10x slower. Parcelable is the strict Android Best Practice.
data class DayDialogData(
    val slot: Int,
    val day: Int,
) : Parcelable

/**
 * Данные диалога корректировки остатка.
 * Parcelable для сохранения в SavedStateHandle.
 *
 * @property slot Индекс слота SIM.
 * @property currentValue Текущее значение остатка.
 * @property isMinutes true если корректировка минут, false если трафика.
 */
@Parcelize
// Using Parcelable instead of Serializable for Android IPC/Bundle transport.
// Serializable is fragile: schema changes break deserialization silently,
// and it's ~10x slower. Parcelable is the strict Android Best Practice.
data class AdjustDialogData(
    val slot: Int,
    val currentValue: Float,
    val isMinutes: Boolean,
) : Parcelable

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
