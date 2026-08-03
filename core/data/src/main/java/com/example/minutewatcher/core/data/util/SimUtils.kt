package com.example.minutewatcher.core.data.util

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.CancellationException
import java.util.Locale

object SimUtils {
    private val operatorMap = mapOf(
        "mts" to R.string.operator_mts,
        "beeline" to R.string.operator_beeline,
        "megafon" to R.string.operator_megafon,
        "tele2" to R.string.operator_tele2,
        "t2" to R.string.operator_tele2,
        "mgts" to R.string.operator_mgts,
        "yota" to R.string.operator_yota,
        "vodafone" to R.string.operator_vodafone,
        "t-mobile" to R.string.operator_t_mobile,
        "telekom" to R.string.operator_t_mobile,
        "orange" to R.string.operator_orange,
        "o2" to R.string.operator_o2,
        "at&t" to R.string.operator_att,
        "verizon" to R.string.operator_verizon,
        "tim" to R.string.operator_tim,
        "sfr" to R.string.operator_sfr,
        "bouygues" to R.string.operator_bouygues,
        "windtre" to R.string.operator_windtre,
        "movistar" to R.string.operator_movistar,
        "claro" to R.string.operator_claro,
        "airtel" to R.string.operator_airtel,
        "jio" to R.string.operator_jio,
        "kpn" to R.string.operator_kpn,
        "proximus" to R.string.operator_proximus,
        "swisscom" to R.string.operator_swisscom,
        "sunrise" to R.string.operator_sunrise,
        "salt" to R.string.operator_salt,
        "telia" to R.string.operator_telia,
        "telenor" to R.string.operator_telenor,
        "elisa" to R.string.operator_elisa,
        "dna" to R.string.operator_dna,
        "plus" to R.string.operator_plus,
        "play" to R.string.operator_play,
        "turkcell" to R.string.operator_turkcell,
        "turk telekom" to R.string.operator_turk_telekom,
    )

    fun getOperatorIconResource(operatorName: String): Int {
        val entry = operatorMap.entries.find { (token, _) -> operatorName.hasToken(token) }
        return when (entry?.value) {
            R.string.operator_mts -> R.drawable.ic_mts
            R.string.operator_beeline -> R.drawable.ic_beeline
            R.string.operator_megafon -> R.drawable.ic_megafon
            R.string.operator_tele2 -> R.drawable.ic_tele2
            R.string.operator_mgts -> R.drawable.ic_mgts
            R.string.operator_yota -> R.drawable.ic_yota
            else -> R.drawable.ic_unknown
        }
    }

    fun getShortOperatorName(
        context: Context,
        operatorName: String,
    ): String {
        val localizedContext = getLocalizedContext(context)
        return try {
            val entry = operatorMap.entries.find { (token, _) -> operatorName.hasToken(token) }
            if (entry != null) {
                getStringSafe(localizedContext, entry.value, entry.key.uppercase(Locale.ROOT))
            } else if (operatorName.isNotEmpty()) {
                truncateName(operatorName)
            } else {
                localizedContext.getString(R.string.no_sim_found)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.e("Error resolving short operator name for: $operatorName", e)
            if (operatorName.isNotEmpty()) truncateName(operatorName) else "SIM"
        }
    }

    private fun truncateName(name: String): String =
        if (name.length > MAX_OPERATOR_NAME_LENGTH) {
            name.take(TRUNCATED_OPERATOR_NAME_LENGTH) + "..."
        } else {
            name
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
        val normalized = lowercase(Locale.ROOT)
        val targetToken = token.lowercase(Locale.ROOT)

        // 1. Check exact match
        if (normalized == targetToken) return true

        // 2. Check in tokens split by common delimiters (excluding dash if token has it)
        val regex = if (targetToken.contains("-")) "[^a-z0-9&-]+" else "[^a-z0-9&]+"
        val tokens = normalized.split(Regex(regex)).filter { it.isNotEmpty() }

        return tokens.contains(targetToken)
    }
}
