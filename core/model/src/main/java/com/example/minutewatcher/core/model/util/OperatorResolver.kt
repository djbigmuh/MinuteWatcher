package com.example.minutewatcher.core.model.util

import android.content.Context
import com.example.minutewatcher.core.model.R
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Вынесение логики определения оператора (Этап 3, п. 13).
 * Исправлены ошибки operatorName.size и некорректный false.contains("SIM").
 * Локализация (Этап 2, п. 1).
 */
object OperatorResolver {
    private var db: Map<String, String>? = null

    private fun loadDb(context: Context): Map<String, String> {
        val currentDb = db
        if (currentDb != null) return currentDb

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
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun resolve(context: Context, operatorName: String, networkOperator: String, simSlot: Int): String {
        // If name is already a valid specific name, keep it
        if (operatorName.isNotEmpty() && 
            operatorName != "null" && 
            !operatorName.contains("SIM", ignoreCase = true) && 
            !SimUtils.isInactiveSim(operatorName)) {
            return operatorName
        }

        val database = loadDb(context)
        val resKey = database[networkOperator]

        if (resKey != null) {
            val resId = context.resources.getIdentifier(resKey, "string", context.packageName)
            if (resId != 0) {
                return context.getString(resId)
            }
            // If it's not a resource key, it might be a literal name from the DB
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

