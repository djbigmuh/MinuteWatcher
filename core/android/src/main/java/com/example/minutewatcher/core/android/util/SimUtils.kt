package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R

object SimUtils {
    @VisibleForTesting
    internal val operatorIconMap =
        mapOf(
            R.string.operator_mts to R.drawable.ic_operator_mts,
            R.string.operator_megafon to R.drawable.ic_operator_megafon,
            R.string.operator_beeline to R.drawable.ic_operator_beeline,
            R.string.operator_tele2 to R.drawable.ic_operator_tele2,
            R.string.operator_mgts to R.drawable.ic_operator_mgts,
            R.string.operator_yota to R.drawable.ic_operator_yota,
            R.string.operator_vodafone to R.drawable.ic_operator_vodafone,
            R.string.operator_t_mobile to R.drawable.ic_operator_t_mobile,
            R.string.operator_orange to R.drawable.ic_operator_orange,
            R.string.operator_o2 to R.drawable.ic_operator_o2,
            R.string.operator_att to R.drawable.ic_operator_att,
            R.string.operator_verizon to R.drawable.ic_operator_verizon,
            R.string.operator_tim to R.drawable.ic_operator_tim,
            R.string.operator_vodafone_it to R.drawable.ic_operator_vodafone,
            R.string.operator_windtre to R.drawable.ic_operator_windtre,
            R.string.operator_sfr to R.drawable.ic_operator_sfr,
            R.string.operator_bouygues to R.drawable.ic_operator_bouygues,
            R.string.operator_telekom to R.drawable.ic_operator_telekom,
            R.string.operator_ntt_docomo to R.drawable.ic_operator_ntt_docomo,
            R.string.operator_softbank to R.drawable.ic_operator_softbank,
            R.string.operator_kddi to R.drawable.ic_operator_kddi,
            R.string.operator_china_mobile to R.drawable.ic_operator_china_mobile,
            R.string.operator_china_unicom to R.drawable.ic_operator_china_unicom,
            R.string.operator_china_telecom to R.drawable.ic_operator_china_telecom,
            R.string.operator_jio to R.drawable.ic_operator_jio,
            R.string.operator_airtel to R.drawable.ic_operator_airtel,
            R.string.operator_vi to R.drawable.ic_operator_vi,
            R.string.operator_stc to R.drawable.ic_operator_stc,
            R.string.operator_mobily to R.drawable.ic_operator_mobily,
            R.string.operator_zain to R.drawable.ic_operator_zain,
            R.string.operator_etisalat to R.drawable.ic_operator_etisalat,
            R.string.operator_du to R.drawable.ic_operator_du,
            R.string.operator_kpn to R.drawable.ic_operator_kpn,
            R.string.operator_proximus to R.drawable.ic_operator_proximus,
            R.string.operator_swisscom to R.drawable.ic_operator_swisscom,
            R.string.operator_sunrise to R.drawable.ic_operator_sunrise,
            R.string.operator_salt to R.drawable.ic_operator_salt,
            R.string.operator_telia to R.drawable.ic_operator_telia,
            R.string.operator_telenor to R.drawable.ic_operator_telenor,
            R.string.operator_elisa to R.drawable.ic_operator_elisa,
            R.string.operator_dna to R.drawable.ic_operator_dna,
            R.string.operator_plus to R.drawable.ic_operator_plus,
            R.string.operator_play to R.drawable.ic_operator_play,
            R.string.operator_turkcell to R.drawable.ic_operator_turkcell,
            R.string.operator_turk_telekom to R.drawable.ic_operator_turk_telekom,
            R.string.operator_rostelecom to R.drawable.ic_operator_rostelecom,
            R.string.operator_tattelecom to R.drawable.ic_operator_tattelecom,
            R.string.operator_a1 to R.drawable.ic_operator_a1,
            R.string.operator_three to R.drawable.ic_operator_three,
            R.string.operator_base to R.drawable.ic_operator_base,
            R.string.operator_tdc to R.drawable.ic_operator_tdc,
            R.string.operator_ice to R.drawable.ic_operator_ice,
            R.string.operator_maxis to R.drawable.ic_operator_maxis,
            R.string.operator_celcom to R.drawable.ic_operator_celcom,
            R.string.operator_digi to R.drawable.ic_operator_digi,
            R.string.operator_u_mobile to R.drawable.ic_operator_u_mobile,
            R.string.operator_singtel to R.drawable.ic_operator_singtel,
            R.string.operator_m1 to R.drawable.ic_operator_m1,
            R.string.operator_starhub to R.drawable.ic_operator_starhub,
            R.string.operator_telstra to R.drawable.ic_operator_telstra,
            R.string.operator_optus to R.drawable.ic_operator_optus,
            R.string.operator_spark to R.drawable.ic_operator_spark,
            R.string.operator_2degrees to R.drawable.ic_operator_2degrees,
            R.string.operator_movistar to R.drawable.ic_operator_movistar,
            R.string.operator_claro to R.drawable.ic_operator_claro,
        )

    fun getOperatorIconResource(operatorName: String): Int {
        val resId = OperatorResolver.getOperatorResourceId(operatorName)
        return resId?.let { operatorIconMap[it] } ?: R.drawable.ic_unknown
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
        return try {
            val resId = OperatorResolver.getOperatorResourceId(operatorName)
            when {
                resId != null -> getStringSafe(localizedContext, resId, operatorName)
                operatorName.isNotEmpty() -> operatorName
                else -> genericOperatorName(localizedContext, slot)
            }
        } catch (e: Exception) {
            AppLogger.e("Error resolving short operator name for: $operatorName", e)
            if (operatorName.isNotEmpty()) operatorName else genericOperatorName(localizedContext, slot)
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
