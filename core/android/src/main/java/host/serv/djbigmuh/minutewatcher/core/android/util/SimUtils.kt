package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.resources.R

object SimUtils {
    fun getOperatorIconResource(brandKey: String?): Int {
        if (brandKey.isNullOrEmpty()) return R.drawable.ic_unknown
        val resolvedKey =
            if (brandKey.startsWith("operator_")) {
                brandKey
            } else {
                OperatorResolver.getOperatorBrandKey(brandKey)
            }
        return resolvedKey?.let { OperatorBrandDatabase.operatorIconByKeyMap[it] } ?: R.drawable.ic_unknown
    }

    /**
     * Resolves the operator name for display.
     * Returns the full resolved operator name or generic SIM name if operator name is empty.
     * Visual truncation should be handled by UI display attributes (such as maxLines = 1 and TextOverflow.Ellipsis).
     *
     * @param context Context for resource resolution.
     * @param operatorName Raw operator name or code.
     * @param slot SIM slot index.
     * @return Full resolved operator name or generic fallback.
     */
    fun getShortOperatorName(
        context: Context,
        operatorName: String,
        slot: Int,
    ): String {
        val localizedContext = AppLocaleContexts.forAppLocale(context)
        val trimmed = operatorName.trim()
        if (OperatorResolver.isValidSpecificName(trimmed)) {
            return trimmed
        }
        return try {
            val resId = OperatorResolver.getOperatorResourceId(operatorName)
            when {
                resId != null -> getStringSafe(localizedContext, resId, operatorName)
                operatorName.isNotEmpty() -> trimmed
                else -> genericOperatorName(localizedContext, slot)
            }
        } catch (e: Exception) {
            AppLogger.e("Error resolving short operator name for: $operatorName", e)
            if (operatorName.isNotEmpty()) trimmed else genericOperatorName(localizedContext, slot)
        }
    }

    private fun genericOperatorName(
        context: Context,
        slot: Int,
    ): String = context.getString(R.string.operator_generic, slot + 1)

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
