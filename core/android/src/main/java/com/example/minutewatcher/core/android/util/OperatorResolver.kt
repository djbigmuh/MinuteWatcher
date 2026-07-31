package com.example.minutewatcher.core.android.util

import android.content.Context
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

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
        // If name is already a valid specific name, keep it
        if (operatorName.isNotEmpty() &&
            operatorName != "null" &&
            !genericSimRegex.matches(operatorName.trim()) &&
            !SimUtils.isInactiveSim(operatorName)
        ) {
            return operatorName
        }

        val database = loadDb(context)
        val resKey = database[networkOperator]

        if (resKey != null) {
            val resId = operatorResourceMap[resKey]
            if (resId != null) {
                return context.getString(resId)
            }
            // If it's not in the resource map, it might be a literal name from the DB
            if (!resKey.startsWith("operator_")) {
                return resKey
            }
        }

        // Final fallback to MCC/MNC info or generic "SIM X"
        return if (networkOperator.isNotEmpty()) {
            if (networkOperator.length >= 5) {
                val mcc = networkOperator.take(3)
                val mnc = networkOperator.drop(3)
                context.getString(R.string.operator_with_mcc, "MCC:$mcc MNC:$mnc")
            } else {
                context.getString(R.string.operator_with_mcc, networkOperator)
            }
        } else {
            context.getString(R.string.operator_generic, simSlot + 1)
        }
    }
}
