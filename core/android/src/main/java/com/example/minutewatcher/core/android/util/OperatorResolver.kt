package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.resources.R
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale

/**
 * Logic for resolving operator name from MCC/MNC database.
 * Optimized with thread-safe cache and explicit resource mapping.
 */
@Suppress("TooManyFunctions")
object OperatorResolver {
    @Volatile
    private var db: Map<String, String>? = null

    private val genericSimRegex = Regex("^sim\\s*\\d*$", RegexOption.IGNORE_CASE)

    @VisibleForTesting
    internal val operatorResourceMap =
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

    @VisibleForTesting
    internal val tokenToResKey =
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

    @VisibleForTesting
    fun resetForTest() {
        synchronized(this) {
            db = null
        }
    }

    /**
     * Returns the cached MCC/MNC database.
     * Uses Double-Checked Locking for thread-safe initialization.
     */
    private fun loadDb(context: Context): Map<String, String> =
        db ?: synchronized(this) {
            // If loadDbInternal returns emptyMap (on error), this value will be cached
            // in db, preventing repeated attempts to read corrupted assets.
            db ?: loadDbInternal(context).also { db = it }
        }

    /**
     * Loads the MCC/MNC database from assets.
     */
    private fun loadDbInternal(context: Context): Map<String, String> =
        try {
            val inputStream = context.assets.open("mcc_mnc_db.json")
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val jsonString = reader.use { it.readText() }

            val jsonObject = JSONObject(jsonString)
            val map = mutableMapOf<String, String>()
            jsonObject.keys().forEach { key ->
                map[key] = jsonObject.getString(key)
            }
            map
        } catch (e: IOException) {
            AppLogger.e("Failed to load MCC/MNC database from assets", e)
            emptyMap()
        } catch (e: Exception) {
            // Catches all other exceptions to ensure db is set to emptyMap
            AppLogger.e("Unexpected error loading MCC/MNC database", e)
            emptyMap()
        }

    /**
     * Data structure to return both resolved name and the code that matched.
     */
    data class Resolution(
        val name: String,
        val matchedCode: String?,
    )

    /**
     * Resolves operator name using a list of MCC/MNC candidates.
     * Returns the code that actually matched the database if any.
     */
    fun resolveWithMatchedCode(
        context: Context,
        operatorName: String,
        candidates: List<String>,
        simSlot: Int,
    ): Resolution {
        if (isValidSpecificName(operatorName)) return Resolution(operatorName, null)

        val database = loadDb(context)
        val searchResult = findInDatabase(database, candidates)

        val resKey = searchResult.first
        val matchedCode = searchResult.second

        val resolved =
            if (resKey != null) {
                resolveFromDbKey(context, resKey)
            } else {
                logUnknownCandidates(candidates)
                null
            }

        return if (resolved != null) {
            Resolution(resolved, matchedCode)
        } else {
            Resolution(fallbackResolution(context, candidates.firstOrNull().orEmpty(), simSlot), null)
        }
    }

    private fun findInDatabase(
        database: Map<String, String>,
        candidates: List<String>,
    ): Pair<String?, String?> {
        for (code in candidates) {
            if (code.isNotEmpty() && code != "00000") {
                val key = database[code]
                if (key != null) {
                    return key to code
                }
            }
        }
        return null to null
    }

    private fun logUnknownCandidates(candidates: List<String>) {
        val primaryCode = candidates.firstOrNull().orEmpty()
        if (primaryCode.isNotEmpty() && primaryCode != "00000") {
            AppLogger.w(
                "Unknown MCC/MNC candidates detected: $candidates. " +
                    "Consider adding it to mcc_mnc_db.json",
            )
        }
    }

    /**
     * Checks if the operator name is specific and valid.
     *
     * **Validity:** a name is considered valid if it is not an inactive SIM
     * (empty or "null") and does not match the generic "SIM N" pattern.
     *
     * @param name Operator name to check.
     * @return True if the name is specific and can be used as is.
     */
    private fun isValidSpecificName(name: String): Boolean =
        !SimUtils.isInactiveSim(name) && !genericSimRegex.matches(name.trim())

    private fun resolveFromDbKey(
        context: Context,
        resKey: String,
    ): String? =
        operatorResourceMap[resKey]?.let { context.getString(it) }
            ?: resKey.takeIf { !it.startsWith("operator_") }

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

    /**
     * Resolves the operator resource ID from the given operator name.
     * Uses strict token-based matching to prevent partial matches (e.g., "Timor" matching "TIM").
     */
    fun getOperatorResourceId(operatorName: String): Int? =
        tokenToResKey.entries
            .find { (token, _) -> operatorName.hasToken(token) }
            ?.let { operatorResourceMap[it.value] }

    /**
     * Проверяет, содержит ли строка [targetToken] как отдельный токен.
     *
     * **Строгое соблюдение API:** сравнение выполняется в нижнем регистре
     * ([Locale.ROOT]), что гарантирует независимость от регистра входных данных.
     * Токены извлекаются сплитом по не-алфавитно-цифровым символам,
     * что исключает частичное совпадение (например, "Timor" не совпадёт с "TIM").
     *
     * **Эвристика в пределах критерия надёжности:** сплит по `[^a-z0-9&-]+`
     * (или `[^a-z0-9&]+` если токен содержит дефис) — стандартный подход
     * для токенизации имён операторов. Дефис сохраняется как разделитель
     * только если сам токен его содержит (например, "t-mobile").
     */
    private fun String.hasToken(targetToken: String): Boolean {
        val normalized = lowercase(Locale.ROOT)
        val target = targetToken.lowercase(Locale.ROOT)

        // 1. Check exact match
        if (normalized == target) return true

        // 2. Check in tokens split by common delimiters (excluding dash if token has it)
        val regex = if (target.contains("-")) "[^a-z0-9&-]+" else "[^a-z0-9&]+"
        val tokens = normalized.split(Regex(regex)).filter { it.isNotEmpty() }

        return tokens.contains(target)
    }
}
