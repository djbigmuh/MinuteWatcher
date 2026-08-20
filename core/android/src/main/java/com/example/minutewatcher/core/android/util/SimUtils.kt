package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R
import kotlinx.coroutines.CancellationException

object SimUtils {
    fun getOperatorIconResource(operatorName: String): Int =
        when (OperatorResolver.getOperatorResourceId(operatorName)) {
            R.string.operator_mts -> R.drawable.ic_mts
            R.string.operator_beeline -> R.drawable.ic_beeline
            R.string.operator_megafon -> R.drawable.ic_megafon
            R.string.operator_tele2 -> R.drawable.ic_tele2
            R.string.operator_mgts -> R.drawable.ic_mgts
            R.string.operator_yota -> R.drawable.ic_yota
            else -> R.drawable.ic_unknown
        }

    fun getShortOperatorName(
        context: Context,
        operatorName: String,
    ): String {
        val localizedContext = getLocalizedContext(context)
        return try {
            val resId = OperatorResolver.getOperatorResourceId(operatorName)
            when {
                resId != null -> getStringSafe(localizedContext, resId, operatorName.uppercase())
                operatorName.isNotEmpty() -> truncateName(operatorName)
                else -> localizedContext.getString(R.string.no_sim_found)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("Resource not found or failed to load: $resId. Using default: $default", e)
            default
        }

    // Кастомный language switcher в приложении использует AppCompatDelegate.setApplicationLocales(),
    // что не требует android:localeConfig в манифесте. Это строгое соответствие API 33+.
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

    fun isInactiveSim(operatorName: String?): Boolean =
        operatorName.isNullOrEmpty() || operatorName.equals("null", ignoreCase = true)
}
