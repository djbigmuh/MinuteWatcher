package com.example.minutewatcher.core.model.util

import android.content.Context
import com.example.minutewatcher.core.model.R
import java.util.Locale

object SimUtils {

    fun getOperatorIconResource(operatorName: String): Int {
        val cleanName = operatorName.lowercase(Locale.ROOT)
        return when {
            cleanName.contains("mts") -> R.drawable.ic_mts
            cleanName.contains("beeline") -> R.drawable.ic_beeline
            cleanName.contains("megafon") -> R.drawable.ic_megafon
            cleanName.contains("tele2") || cleanName.contains("t2") -> R.drawable.ic_tele2
            cleanName.contains("mgts") -> R.drawable.ic_mgts
            cleanName.contains("yota") -> R.drawable.ic_yota
            else -> R.drawable.ic_unknown
        }
    }

    fun getShortOperatorName(context: Context, operatorName: String): String {
        val name = operatorName.lowercase(Locale.ROOT)
        val localizedContext = getLocalizedContext(context)
        
        return try {
            when {
                name.contains("mts") -> getStringSafe(localizedContext, R.string.operator_mts, "MTS")
                name.contains("beeline") -> getStringSafe(localizedContext, R.string.operator_beeline, "Beeline")
                name.contains("megafon") -> getStringSafe(localizedContext, R.string.operator_megafon, "MegaFon")
                name.contains("tele2") || name.contains("t2") -> getStringSafe(localizedContext, R.string.operator_tele2, "Tele2")
                name.contains("mgts") -> getStringSafe(localizedContext, R.string.operator_mgts, "MGTS")
                name.contains("yota") -> getStringSafe(localizedContext, R.string.operator_yota, "Yota")
                name.contains("vodafone") -> getStringSafe(localizedContext, R.string.operator_vodafone, "Vodafone")
                name.contains("t-mobile") || name.contains("telekom") -> getStringSafe(localizedContext, R.string.operator_t_mobile, "T-Mobile")
                name.contains("orange") -> getStringSafe(localizedContext, R.string.operator_orange, "Orange")
                name.contains("o2") -> "O2"
                name.contains("at&t") -> getStringSafe(localizedContext, R.string.operator_att, "AT&T")
                name.contains("verizon") -> getStringSafe(localizedContext, R.string.operator_verizon, "Verizon")
                name.contains("tim") -> getStringSafe(localizedContext, R.string.operator_tim, "TIM")
                name.contains("sfr") -> getStringSafe(localizedContext, R.string.operator_sfr, "SFR")
                name.contains("bouygues") -> getStringSafe(localizedContext, R.string.operator_bouygues, "Bouygues")
                name.contains("windtre") -> getStringSafe(localizedContext, R.string.operator_windtre, "WindTre")
                name.contains("movistar") -> "Movistar"
                name.contains("claro") -> "Claro"
                name.contains("airtel") -> "Airtel"
                name.contains("jio") -> "Jio"
                else -> if (operatorName.isNotEmpty()) {
                    if (operatorName.length > 15) operatorName.take(12) + "..." else operatorName
                } else localizedContext.getString(R.string.no_sim_found)
            }
        } catch (_: Exception) {
            if (operatorName.isNotEmpty()) {
                if (operatorName.length > 15) operatorName.take(12) + "..." else operatorName
            } else "SIM"
        }
    }

    private fun getStringSafe(context: Context, resId: Int, default: String): String {
        return try {
            context.getString(resId)
        } catch (_: Exception) {
            default
        }
    }

    private fun getLocalizedContext(context: Context): Context {
        val appLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
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

    fun isInactiveSim(operatorName: String?): Boolean {
        return operatorName.isNullOrEmpty() || operatorName.equals("null", ignoreCase = true)
    }
}
