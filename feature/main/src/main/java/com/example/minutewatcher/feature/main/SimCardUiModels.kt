package com.example.minutewatcher.feature.main

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Limit setting dialog data.
 * Parcelable for SavedStateHandle persistence.
 *
 * @property slot SIM slot index.
 * @property name Operator name.
 * @property minutes Current minutes limit.
 * @property traffic Current traffic limit (MB).
 * @property rolloverEnabled Whether rollover is enabled.
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
 * Auto-reset day picker dialog data.
 * Parcelable for SavedStateHandle persistence.
 *
 * @property slot SIM slot index.
 * @property day Current auto-reset day.
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
 * Remaining usage adjustment dialog data.
 * Parcelable for SavedStateHandle persistence.
 *
 * @property slot SIM slot index.
 * @property currentValue Current remaining value.
 * @property isMinutes true for minutes adjustment, false for traffic.
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
 * Language option for LanguageMenu.
 *
 * @property code Language code (null for system default).
 * @property name Language name.
 * @property flag Language flag/emoji.
 */
data class LanguageOption(
    val code: String?,
    val name: String,
    val flag: String,
)
