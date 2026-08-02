package com.example.minutewatcher.core.data.util

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.CancellationException
import java.util.Locale

object SimUtils {
    fun getOperatorIconResource(operatorName: String): Int =
        when {
            operatorName.hasToken("mts") -> R.drawable.ic_mts
            operatorName.hasToken("beeline") -> R.drawable.ic_beeline
            operatorName.hasToken("megafon") -> R.drawable.ic_megafon
            operatorName.hasToken("tele2") || operatorName.hasToken("t2") -> R.drawable.ic_tele2
            operatorName.hasToken("mgts") -> R.drawable.ic_mgts
            operatorName.hasToken("yota") -> R.drawable.ic_yota
            else -> R.drawable.ic_unknown
        }

    fun getShortOperatorName(
        context: Context,
        operatorName: String,
    ): String {
        val localizedContext = getLocalizedContext(context)

        return try {
            when {
                operatorName.hasToken("mts") -> getStringSafe(localizedContext, R.string.operator_mts, "MTS")
                operatorName.hasToken("beeline") -> getStringSafe(localizedContext, R.string.operator_beeline, "Beeline")
                operatorName.hasToken("megafon") -> getStringSafe(localizedContext, R.string.operator_megafon, "MegaFon")
                operatorName.hasToken(
                    "tele2",
                ) ||
                    operatorName.hasToken("t2") -> getStringSafe(localizedContext, R.string.operator_tele2, "Tele2")
                operatorName.hasToken("mgts") -> getStringSafe(localizedContext, R.string.operator_mgts, "MGTS")
                operatorName.hasToken("yota") -> getStringSafe(localizedContext, R.string.operator_yota, "Yota")
                operatorName.hasToken("vodafone") -> getStringSafe(localizedContext, R.string.operator_vodafone, "Vodafone")
                operatorName.hasToken(
                    "t-mobile",
                ) ||
                    operatorName.hasToken("telekom") -> getStringSafe(localizedContext, R.string.operator_t_mobile, "T-Mobile")
                operatorName.hasToken("orange") -> getStringSafe(localizedContext, R.string.operator_orange, "Orange")
                operatorName.hasToken("o2") -> getStringSafe(localizedContext, R.string.operator_o2, "O2")
                operatorName.hasToken("at&t") -> getStringSafe(localizedContext, R.string.operator_att, "AT&T")
                operatorName.hasToken("verizon") -> getStringSafe(localizedContext, R.string.operator_verizon, "Verizon")
                operatorName.hasToken("tim") -> getStringSafe(localizedContext, R.string.operator_tim, "TIM")
                operatorName.hasToken("sfr") -> getStringSafe(localizedContext, R.string.operator_sfr, "SFR")
                operatorName.hasToken("bouygues") -> getStringSafe(localizedContext, R.string.operator_bouygues, "Bouygues")
                operatorName.hasToken("windtre") -> getStringSafe(localizedContext, R.string.operator_windtre, "WindTre")
                operatorName.hasToken("movistar") -> getStringSafe(localizedContext, R.string.operator_movistar, "Movistar")
                operatorName.hasToken("claro") -> getStringSafe(localizedContext, R.string.operator_claro, "Claro")
                operatorName.hasToken("airtel") -> getStringSafe(localizedContext, R.string.operator_airtel, "Airtel")
                operatorName.hasToken("jio") -> getStringSafe(localizedContext, R.string.operator_jio, "Jio")
                operatorName.hasToken("kpn") -> getStringSafe(localizedContext, R.string.operator_kpn, "KPN")
                operatorName.hasToken("proximus") -> getStringSafe(localizedContext, R.string.operator_proximus, "Proximus")
                operatorName.hasToken("swisscom") -> getStringSafe(localizedContext, R.string.operator_swisscom, "Swisscom")
                operatorName.hasToken("sunrise") -> getStringSafe(localizedContext, R.string.operator_sunrise, "Sunrise")
                operatorName.hasToken("salt") -> getStringSafe(localizedContext, R.string.operator_salt, "Salt")
                operatorName.hasToken("telia") -> getStringSafe(localizedContext, R.string.operator_telia, "Telia")
                operatorName.hasToken("telenor") -> getStringSafe(localizedContext, R.string.operator_telenor, "Telenor")
                operatorName.hasToken("elisa") -> getStringSafe(localizedContext, R.string.operator_elisa, "Elisa")
                operatorName.hasToken("dna") -> getStringSafe(localizedContext, R.string.operator_dna, "DNA")
                operatorName.hasToken("plus") -> getStringSafe(localizedContext, R.string.operator_plus, "Plus")
                operatorName.hasToken("play") -> getStringSafe(localizedContext, R.string.operator_play, "Play")
                operatorName.hasToken("turkcell") -> getStringSafe(localizedContext, R.string.operator_turkcell, "Turkcell")
                operatorName.hasToken("turk telekom") -> getStringSafe(localizedContext, R.string.operator_turk_telekom, "Türk Telekom")
                else ->
                    if (operatorName.isNotEmpty()) {
                        if (operatorName.length > MAX_OPERATOR_NAME_LENGTH) {
                            operatorName.take(TRUNCATED_OPERATOR_NAME_LENGTH) + "..."
                        } else {
                            operatorName
                        }
                    } else {
                        localizedContext.getString(R.string.no_sim_found)
                    }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.e("Error resolving short operator name for: $operatorName", e)
            if (operatorName.isNotEmpty()) {
                if (operatorName.length > MAX_OPERATOR_NAME_LENGTH) {
                    operatorName.take(TRUNCATED_OPERATOR_NAME_LENGTH) + "..."
                } else {
                    operatorName
                }
            } else {
                "SIM"
            }
        }
    }

    private const val MAX_OPERATOR_NAME_LENGTH = 20
    private const val TRUNCATED_OPERATOR_NAME_LENGTH = 17

    private fun getStringSafe(
        context: Context,
        resId: Int,
        default: String,
    ): String =
        try {
            context.getString(resId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.w("Resource not found or failed to load: $resId. Using default: $default")
            default
        }

    // AppBundleLocaleChanges is suppressed because we need a temporary context with a specific locale
    // to resolve operator names from resources correctly when the app language is manually changed.
    @Suppress("AppBundleLocaleChanges")
    private fun getLocalizedContext(context: Context): Context {
        val appLocales =
            androidx.appcompat.app.AppCompatDelegate
                .getApplicationLocales()
        if (!appLocales.isEmpty) {
            val locale = appLocales.get(0)
            if (locale != null) {
                val config = android.content.res.Configuration(context.resources.configuration)
                config.setLocale(locale)
                return context.createConfigurationContext(config)
            }
        }
        return context
    }

    fun isInactiveSim(operatorName: String?): Boolean = operatorName.isNullOrEmpty() || operatorName.equals("null", ignoreCase = true)

    private fun String.hasToken(token: String): Boolean {
        val tokens =
            lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9&-]+"))
                .filter { it.isNotEmpty() }
        return tokens.contains(token.lowercase(Locale.ROOT))
    }
}
