package com.example.minutewatcher.feature.main

import android.os.Parcelable
import androidx.annotation.StringRes
import com.example.minutewatcher.core.resources.R
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
 * @property nameRes String resource ID for language name.
 * @property flag Language flag/emoji.
 */
data class LanguageOption(
    val code: String?,
    @StringRes val nameRes: Int,
    val flag: String,
)

val supportedLanguageOptions: List<LanguageOption> =
    listOf(
        LanguageOption(null, R.string.language_system, "🌐"),
        LanguageOption("en", R.string.language_en, "🇺🇸"),
        LanguageOption("ru", R.string.language_ru, "🇷🇺"),
        LanguageOption("de", R.string.language_de, "🇩🇪"),
        LanguageOption("fr", R.string.language_fr, "🇫🇷"),
        LanguageOption("es", R.string.language_es, "🇪🇸"),
        LanguageOption("ar", R.string.language_ar, "🇸🇦"),
        LanguageOption("hi", R.string.language_hi, "🇮🇳"),
        LanguageOption("ja", R.string.language_ja, "🇯🇵"),
        LanguageOption("zh", R.string.language_zh, "🇨🇳"),
    )
