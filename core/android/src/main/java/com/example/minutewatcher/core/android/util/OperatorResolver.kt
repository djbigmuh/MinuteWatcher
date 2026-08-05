package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale

/**
 * Logic for resolving operator name from MCC/MNC database.
 * Optimized with thread-safe cache and explicit resource mapping.
 */
object OperatorResolver {
    @Volatile
    private var db: Map<String, String>? = null

    private val genericSimRegex = Regex("^sim\\s*\\d*$", RegexOption.IGNORE_CASE)

    private val operatorResourceMap =
        mapOf(
            "operator_mts" to R.string.operator_mts,
            "operator_megafon" to R.string.operator_megafon,
            "operator_beeline" to R.string.operator_beeline,
            "operator_tele2" to R.string.operator_tele2,
            "operator_mgts" to R.string.operator_mgts,
            "operator_yota" to R.string.operator_yota,
            "operator_vodafone" to R.string.operator_vodafone,
            "operator_t_mobile" to R.string.operator_t_mobile,
            "operator_orange" to R.string.operator_orange,
            "operator_o2" to R.string.operator_o2,
            "operator_att" to R.string.operator_att,
            "operator_verizon" to R.string.operator_verizon,
            "operator_tim" to R.string.operator_tim,
            "operator_vodafone_it" to R.string.operator_vodafone_it,
            "operator_windtre" to R.string.operator_windtre,
            "operator_sfr" to R.string.operator_sfr,
            "operator_bouygues" to R.string.operator_bouygues,
            "operator_telekom" to R.string.operator_telekom,
            "operator_ntt_docomo" to R.string.operator_ntt_docomo,
            "operator_softbank" to R.string.operator_softbank,
            "operator_kddi" to R.string.operator_kddi,
            "operator_china_mobile" to R.string.operator_china_mobile,
            "operator_china_unicom" to R.string.operator_china_unicom,
            "operator_china_telecom" to R.string.operator_china_telecom,
            "operator_jio" to R.string.operator_jio,
            "operator_airtel" to R.string.operator_airtel,
            "operator_vi" to R.string.operator_vi,
            "operator_stc" to R.string.operator_stc,
            "operator_mobily" to R.string.operator_mobily,
            "operator_zain" to R.string.operator_zain,
            "operator_etisalat" to R.string.operator_etisalat,
            "operator_du" to R.string.operator_du,
            "operator_kpn" to R.string.operator_kpn,
            "operator_proximus" to R.string.operator_proximus,
            "operator_swisscom" to R.string.operator_swisscom,
            "operator_sunrise" to R.string.operator_sunrise,
            "operator_salt" to R.string.operator_salt,
            "operator_telia" to R.string.operator_telia,
            "operator_telenor" to R.string.operator_telenor,
            "operator_elisa" to R.string.operator_elisa,
            "operator_dna" to R.string.operator_dna,
            "operator_plus" to R.string.operator_plus,
            "operator_play" to R.string.operator_play,
            "operator_turkcell" to R.string.operator_turkcell,
            "operator_turk_telekom" to R.string.operator_turk_telekom,
            "operator_rostelecom" to R.string.operator_rostelecom,
            "operator_tattelecom" to R.string.operator_tattelecom,
            "operator_a1" to R.string.operator_a1,
            "operator_three" to R.string.operator_three,
            "operator_base" to R.string.operator_base,
            "operator_tdc" to R.string.operator_tdc,
            "operator_ice" to R.string.operator_ice,
            "operator_maxis" to R.string.operator_maxis,
            "operator_celcom" to R.string.operator_celcom,
            "operator_digi" to R.string.operator_digi,
            "operator_u_mobile" to R.string.operator_u_mobile,
            "operator_singtel" to R.string.operator_singtel,
            "operator_m1" to R.string.operator_m1,
            "operator_starhub" to R.string.operator_starhub,
            "operator_telstra" to R.string.operator_telstra,
            "operator_optus" to R.string.operator_optus,
            "operator_spark" to R.string.operator_spark,
            "operator_2degrees" to R.string.operator_2degrees,
            "operator_movistar" to R.string.operator_movistar,
            "operator_claro" to R.string.operator_claro,
        )

    private val tokenToResKey =
        mapOf(
            "mts" to "operator_mts",
            "beeline" to "operator_beeline",
            "megafon" to "operator_megafon",
            "tele2" to "operator_tele2",
            "t2" to "operator_tele2",
            "mgts" to "operator_mgts",
            "yota" to "operator_yota",
            "vodafone" to "operator_vodafone",
            "t-mobile" to "operator_t_mobile",
            "telekom" to "operator_t_mobile",
            "orange" to "operator_orange",
            "o2" to "operator_o2",
            "at&t" to "operator_att",
            "verizon" to "operator_verizon",
            "tim" to "operator_tim",
            "sfr" to "operator_sfr",
            "bouygues" to "operator_bouygues",
            "windtre" to "operator_windtre",
            "movistar" to "operator_movistar",
            "claro" to "operator_claro",
            "airtel" to "operator_airtel",
            "jio" to "operator_jio",
            "kpn" to "operator_kpn",
            "proximus" to "operator_proximus",
            "swisscom" to "operator_swisscom",
            "sunrise" to "operator_sunrise",
            "salt" to "operator_salt",
            "telia" to "operator_telia",
            "telenor" to "operator_telenor",
            "elisa" to "operator_elisa",
            "dna" to "operator_dna",
            "plus" to "operator_plus",
            "play" to "operator_play",
            "turkcell" to "operator_turkcell",
            "turk telekom" to "operator_turk_telekom",
            "rostelecom" to "operator_rostelecom",
            "tattelecom" to "operator_tattelecom",
            "a1" to "operator_a1",
            "three" to "operator_three",
            "base" to "operator_base",
            "tdc" to "operator_tdc",
            "ice" to "operator_ice",
            "maxis" to "operator_maxis",
            "celcom" to "operator_celcom",
            "digi" to "operator_digi",
            "u mobile" to "operator_u_mobile",
            "singtel" to "operator_singtel",
            "m1" to "operator_m1",
            "starhub" to "operator_starhub",
            "telstra" to "operator_telstra",
            "optus" to "operator_optus",
            "spark" to "operator_spark",
            "2degrees" to "operator_2degrees",
        )

    private fun loadDb(context: Context): Map<String, String> {
        db?.let { return it }

        synchronized(this) {
            db?.let { return it }

            return try {
                val inputStream = context.assets.open("mcc_mnc_db.json")
                val reader = BufferedReader(InputStreamReader(inputStream))
                val jsonString = reader.use { it.readText() }
                val jsonObject = JSONObject(jsonString)
                val map = mutableMapOf<String, String>()
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = jsonObject.getString(key)
                }
                db = map
                map
            } catch (e: IOException) {
                AppLogger.e("Failed to load MCC/MNC database from assets", e)
                emptyMap()
            } catch (e: JSONException) {
                AppLogger.e("Malformed MCC/MNC database JSON", e)
                emptyMap()
            } catch (e: Exception) {
                AppLogger.e("Unexpected error loading MCC/MNC database", e)
                emptyMap()
            }
        }
    }

    fun resolve(
        context: Context,
        operatorName: String,
        networkOperator: String,
        simSlot: Int,
    ): String {
        if (isValidSpecificName(operatorName)) return operatorName

        val database = loadDb(context)
        val resKey = database[networkOperator]

        val resolved =
            if (resKey != null) {
                resolveFromDbKey(context, resKey)
            } else {
                if (networkOperator.isNotEmpty() && networkOperator != "00000") {
                    AppLogger.w("Unknown MCC/MNC detected: $networkOperator. Consider adding it to mcc_mnc_db.json")
                }
                null
            }

        return resolved ?: fallbackResolution(context, networkOperator, simSlot)
    }

    private fun isValidSpecificName(name: String): Boolean =
        name.isNotEmpty() &&
            name != "null" &&
            !genericSimRegex.matches(name.trim()) &&
            !SimUtils.isInactiveSim(name)

    private fun resolveFromDbKey(
        context: Context,
        resKey: String,
    ): String? {
        val resId = operatorResourceMap[resKey]
        if (resId != null) {
            return context.getString(resId)
        }
        if (!resKey.startsWith("operator_")) {
            return resKey
        }
        return null
    }

    private fun fallbackResolution(
        context: Context,
        networkOperator: String,
        simSlot: Int,
    ): String =
        if (networkOperator.isNotEmpty()) {
            formatMccMnc(context, networkOperator)
        } else {
            context.getString(R.string.operator_generic, simSlot + 1)
        }

    private fun formatMccMnc(
        context: Context,
        networkOperator: String,
    ): String =
        if (networkOperator.length >= MIN_MCC_MNC_LENGTH) {
            val mcc = networkOperator.take(MCC_LENGTH)
            val mnc = networkOperator.drop(MCC_LENGTH)
            context.getString(R.string.operator_with_mcc, "MCC:$mcc MNC:$mnc")
        } else {
            context.getString(R.string.operator_with_mcc, networkOperator)
        }

    private const val MCC_LENGTH = 3
    private const val MIN_MCC_MNC_LENGTH = 5

    fun getOperatorResourceId(operatorName: String): Int? {
        val entry = tokenToResKey.entries.find { (token, _) -> operatorName.hasToken(token) }
        return entry?.let { operatorResourceMap[it.value] }
    }

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
