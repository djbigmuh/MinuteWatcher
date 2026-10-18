package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.annotation.VisibleForTesting
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.resources.R

object SimUtils {
    @VisibleForTesting
    val operatorIconMap =
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
            R.string.operator_bsnl to R.drawable.ic_operator_bsnl,
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
            R.string.operator_kyivstar to R.drawable.ic_operator_kyivstar,
            R.string.operator_lifecell to R.drawable.ic_operator_lifecell,
            R.string.operator_moldcell to R.drawable.ic_operator_moldcell,
            R.string.operator_life_by to R.drawable.ic_operator_life_by,
            R.string.operator_a1 to R.drawable.ic_operator_a1,
            R.string.operator_three to R.drawable.ic_operator_three,
            R.string.operator_base to R.drawable.ic_operator_base,
            R.string.operator_free to R.drawable.ic_operator_free,
            R.string.operator_odido to R.drawable.ic_operator_odido,
            R.string.operator_magenta to R.drawable.ic_operator_magenta,
            R.string.operator_oneandone to R.drawable.ic_operator_oneandone,
            R.string.operator_yettel to R.drawable.ic_operator_yettel,
            R.string.operator_iliad to R.drawable.ic_operator_iliad,
            R.string.operator_cosmote to R.drawable.ic_operator_cosmote,
            R.string.operator_rakuten to R.drawable.ic_operator_rakuten,
            R.string.operator_onenz to R.drawable.ic_operator_onenz,
            R.string.operator_ais to R.drawable.ic_operator_ais,
            R.string.operator_truemove to R.drawable.ic_operator_truemove,
            R.string.operator_dtac to R.drawable.ic_operator_dtac,
            R.string.operator_mobifone to R.drawable.ic_operator_mobifone,
            R.string.operator_vinaphone to R.drawable.ic_operator_vinaphone,
            R.string.operator_viettel to R.drawable.ic_operator_viettel,
            R.string.operator_celcomdigi to R.drawable.ic_operator_celcomdigi,
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
            R.string.operator_telcel to R.drawable.ic_operator_telcel,
            R.string.operator_vivo to R.drawable.ic_operator_vivo,
            R.string.operator_algar to R.drawable.ic_operator_algar,
            R.string.operator_yoigo to R.drawable.ic_operator_yoigo,
            R.string.operator_euskaltel to R.drawable.ic_operator_euskaltel,
            R.string.operator_telecable to R.drawable.ic_operator_telecable,
            R.string.operator_r to R.drawable.ic_operator_r,
            R.string.operator_simyo to R.drawable.ic_operator_simyo,
            R.string.operator_jazztel to R.drawable.ic_operator_jazztel,
            R.string.operator_amena to R.drawable.ic_operator_amena,
            R.string.operator_masmovil to R.drawable.ic_operator_masmovil,
            R.string.operator_pepephone to R.drawable.ic_operator_pepephone,
            R.string.operator_lowi to R.drawable.ic_operator_lowi,
            R.string.operator_lebara to R.drawable.ic_operator_lebara,
            R.string.operator_lycamobile to R.drawable.ic_operator_lycamobile,
            R.string.operator_finetwork to R.drawable.ic_operator_finetwork,
            R.string.operator_onecall to R.drawable.ic_operator_onecall,
            R.string.operator_mycall to R.drawable.ic_operator_mycall,
            R.string.operator_phonero to R.drawable.ic_operator_phonero,
            R.string.operator_talkmore to R.drawable.ic_operator_talkmore,
            R.string.operator_chilimobil to R.drawable.ic_operator_chilimobil,
            R.string.operator_fjordkraft to R.drawable.ic_operator_fjordkraft,
            R.string.operator_ee to R.drawable.ic_operator_ee,
            R.string.operator_sk_telecom to R.drawable.ic_operator_sk_telecom,
            R.string.operator_kt to R.drawable.ic_operator_kt,
            R.string.operator_lg_uplus to R.drawable.ic_operator_lg_uplus,
            R.string.operator_globe to R.drawable.ic_operator_globe,
            R.string.operator_smart to R.drawable.ic_operator_smart,
            R.string.operator_dito to R.drawable.ic_operator_dito,
            R.string.operator_telkomsel to R.drawable.ic_operator_telkomsel,
            R.string.operator_indosat to R.drawable.ic_operator_indosat,
            R.string.operator_xl_axiata to R.drawable.ic_operator_xl_axiata,
            R.string.operator_smartfren to R.drawable.ic_operator_smartfren,
            R.string.operator_tri_id to R.drawable.ic_operator_tri_id,
            R.string.operator_cht to R.drawable.ic_operator_cht,
            R.string.operator_taiwan_mobile to R.drawable.ic_operator_taiwan_mobile,
            R.string.operator_fareastone to R.drawable.ic_operator_fareastone,
            R.string.operator_csl to R.drawable.ic_operator_csl,
            R.string.operator_three_hk to R.drawable.ic_operator_three_hk,
            R.string.operator_smartone to R.drawable.ic_operator_smartone,
            R.string.operator_ymobile to R.drawable.ic_operator_ymobile,
            R.string.operator_au_kddi to R.drawable.ic_operator_au_kddi,
            R.string.operator_uq_mobile to R.drawable.ic_operator_uq_mobile,
            R.string.operator_mobitel_lk to R.drawable.ic_operator_mobitel_lk,
            R.string.operator_dialog to R.drawable.ic_operator_dialog,
            R.string.operator_hutch_lk to R.drawable.ic_operator_hutch_lk,
            R.string.operator_jazz to R.drawable.ic_operator_jazz,
            R.string.operator_ufone to R.drawable.ic_operator_ufone,
            R.string.operator_zong to R.drawable.ic_operator_zong,
            R.string.operator_grameenphone to R.drawable.ic_operator_grameenphone,
            R.string.operator_robi to R.drawable.ic_operator_robi,
            R.string.operator_banglalink to R.drawable.ic_operator_banglalink,
            R.string.operator_teletalk to R.drawable.ic_operator_teletalk,
            R.string.operator_mytel to R.drawable.ic_operator_mytel,
            R.string.operator_ooredoo_mm to R.drawable.ic_operator_ooredoo_mm,
            R.string.operator_mpt to R.drawable.ic_operator_mpt,
            R.string.operator_vodafone_au to R.drawable.ic_operator_vodafone_au,
            R.string.operator_ntc to R.drawable.ic_operator_ntc,
            R.string.operator_ncell to R.drawable.ic_operator_ncell,
            R.string.operator_cellcard to R.drawable.ic_operator_cellcard,
            R.string.operator_smart_kh to R.drawable.ic_operator_smart_kh,
            R.string.operator_metfone to R.drawable.ic_operator_metfone,
            R.string.operator_lao_telecom to R.drawable.ic_operator_lao_telecom,
            R.string.operator_unitel to R.drawable.ic_operator_unitel,
            R.string.operator_bmobile to R.drawable.ic_operator_bmobile,
            R.string.operator_dst to R.drawable.ic_operator_dst,
        )

    fun getOperatorIconResource(brandKey: String?): Int {
        if (brandKey.isNullOrEmpty()) return R.drawable.ic_unknown
        val resolvedKey =
            if (brandKey.startsWith("operator_")) {
                brandKey
            } else {
                OperatorResolver.getOperatorBrandKey(brandKey)
            }
        val resId = resolvedKey?.let { OperatorResolver.operatorResourceMap[it] }
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
