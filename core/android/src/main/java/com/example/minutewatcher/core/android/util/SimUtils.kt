package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R

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
        slot: Int,
    ): String {
        val localizedContext = AppLocaleContexts.forAppLocale(context)
        return try {
            val resId = OperatorResolver.getOperatorResourceId(operatorName)
            when {
                resId != null -> getStringSafe(localizedContext, resId, truncateName(operatorName))
                operatorName.isNotEmpty() -> truncateName(operatorName)
                else -> genericOperatorName(localizedContext, slot)
            }
        } catch (e: Exception) {
            AppLogger.e("Error resolving short operator name for: $operatorName", e)
            if (operatorName.isNotEmpty()) truncateName(operatorName) else genericOperatorName(localizedContext, slot)
        }
    }

    private fun genericOperatorName(
        context: Context,
        slot: Int,
    ): String = context.getString(R.string.operator_generic, slot + 1)

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
            AppLogger.w("Resource not found or failed to load: $resId. Using default: $default", e)
            default
        }

    /**
     * Checks if the given operator name indicates an inactive or unidentifiable SIM.
     *
     * A SIM card is considered unidentifiable if its operator name is null or an empty string.
     */
    fun isInactiveSim(operatorName: String?): Boolean = operatorName.isNullOrEmpty()
}
