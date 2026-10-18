package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.annotation.VisibleForTesting
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale

/**
 * Immutable result type for operator resolution containing display name and brand key.
 */
data class ResolutionResult(
    val displayName: String,
    val brandKey: String?,
)

/**
 * Logic for resolving operator name from MCC/MNC database.
 * Optimized with thread-safe cache and explicit resource mapping.
 */
object OperatorResolver {
    @Volatile
    private var db: Map<String, String>? = null

    private var loadAttempts = 0

    /**
     * Maximum number of attempts to load the asset before entering terminal state;
     * retries are executed by standard WorkManager mechanism with exponential backoff specified at scheduling time.
     */
    private const val MAX_DB_LOAD_ATTEMPTS = 3

    private val genericSimRegex = Regex("^sim\\s*\\d*$", RegexOption.IGNORE_CASE)

    private val tokenSplitWithDashRegex = Regex("[^a-z0-9&\u0400-\u04FF-]+")
    private val tokenSplitWithoutDashRegex = Regex("[^a-z0-9&\u0400-\u04FF]+")

    /**
     * Immutable mapping of operator brand keys to their corresponding string resource IDs
     * representing the operator display name. Thread-safe and read-only.
     */
    val operatorResourceMap =
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
            "operator_bsnl" to R.string.operator_bsnl,
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
            "operator_kyivstar" to R.string.operator_kyivstar,
            "operator_lifecell" to R.string.operator_lifecell,
            "operator_moldcell" to R.string.operator_moldcell,
            "operator_life_by" to R.string.operator_life_by,
            "operator_a1" to R.string.operator_a1,
            "operator_three" to R.string.operator_three,
            "operator_base" to R.string.operator_base,
            "operator_free" to R.string.operator_free,
            "operator_odido" to R.string.operator_odido,
            "operator_magenta" to R.string.operator_magenta,
            "operator_oneandone" to R.string.operator_oneandone,
            "operator_yettel" to R.string.operator_yettel,
            "operator_iliad" to R.string.operator_iliad,
            "operator_cosmote" to R.string.operator_cosmote,
            "operator_rakuten" to R.string.operator_rakuten,
            "operator_onenz" to R.string.operator_onenz,
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
            "operator_telcel" to R.string.operator_telcel,
            "operator_ais" to R.string.operator_ais,
            "operator_truemove" to R.string.operator_truemove,
            "operator_dtac" to R.string.operator_dtac,
            "operator_mobifone" to R.string.operator_mobifone,
            "operator_vinaphone" to R.string.operator_vinaphone,
            "operator_viettel" to R.string.operator_viettel,
            "operator_celcomdigi" to R.string.operator_celcomdigi,
            "operator_vivo" to R.string.operator_vivo,
            "operator_algar" to R.string.operator_algar,
            "operator_onecall" to R.string.operator_onecall,
            "operator_mycall" to R.string.operator_mycall,
            "operator_phonero" to R.string.operator_phonero,
            "operator_talkmore" to R.string.operator_talkmore,
            "operator_chilimobil" to R.string.operator_chilimobil,
            "operator_fjordkraft" to R.string.operator_fjordkraft,
            "operator_yoigo" to R.string.operator_yoigo,
            "operator_euskaltel" to R.string.operator_euskaltel,
            "operator_telecable" to R.string.operator_telecable,
            "operator_r" to R.string.operator_r,
            "operator_simyo" to R.string.operator_simyo,
            "operator_jazztel" to R.string.operator_jazztel,
            "operator_amena" to R.string.operator_amena,
            "operator_masmovil" to R.string.operator_masmovil,
            "operator_pepephone" to R.string.operator_pepephone,
            "operator_lowi" to R.string.operator_lowi,
            "operator_lebara" to R.string.operator_lebara,
            "operator_lycamobile" to R.string.operator_lycamobile,
            "operator_finetwork" to R.string.operator_finetwork,
            "operator_ee" to R.string.operator_ee,
        )

    @VisibleForTesting
    val tokenToResKey =
        mapOf(
            "mts" to "operator_mts",
            "мтс" to "operator_mts",
            "beeline" to "operator_beeline",
            "билайн" to "operator_beeline",
            "megafon" to "operator_megafon",
            "мегафон" to "operator_megafon",
            "tele2" to "operator_tele2",
            "теле2" to "operator_tele2",
            "t2" to "operator_tele2",
            "т2" to "operator_tele2",
            "mgts" to "operator_mgts",
            "мгтс" to "operator_mgts",
            "yota" to "operator_yota",
            "йота" to "operator_yota",
            "vodafone" to "operator_vodafone",
            "t-mobile" to "operator_t_mobile",
            "telekom" to "operator_telekom",
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
            "telcel" to "operator_telcel",
            "vivo" to "operator_vivo",
            "telefonica vivo" to "operator_vivo",
            "vivo brasil" to "operator_vivo",
            "algar" to "operator_algar",
            "algar telecom" to "operator_algar",
            "airtel" to "operator_airtel",
            "jio" to "operator_jio",
            "vi" to "operator_vi",
            "bsnl" to "operator_bsnl",
            "docomo" to "operator_ntt_docomo",
            "softbank" to "operator_softbank",
            "kddi" to "operator_kddi",
            "china mobile" to "operator_china_mobile",
            "china unicom" to "operator_china_unicom",
            "china telecom" to "operator_china_telecom",
            "stc" to "operator_stc",
            "mobily" to "operator_mobily",
            "zain" to "operator_zain",
            "etisalat" to "operator_etisalat",
            "e&" to "operator_etisalat",
            "du" to "operator_du",
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
            "kyivstar" to "operator_kyivstar",
            "київстар" to "operator_kyivstar",
            "lifecell" to "operator_lifecell",
            "лайфселл" to "operator_lifecell",
            "moldcell" to "operator_moldcell",
            "молдчелл" to "operator_moldcell",
            "life_by" to "operator_life_by",
            "life:)" to "operator_life_by",
            "a1" to "operator_a1",
            "three" to "operator_three",
            "base" to "operator_base",
            "free" to "operator_free",
            "free mobile" to "operator_free",
            "odido" to "operator_odido",
            "magenta" to "operator_magenta",
            "magenta telekom" to "operator_magenta",
            "1&1" to "operator_oneandone",
            "1und1" to "operator_oneandone",
            "eins und eins" to "operator_oneandone",
            "yettel" to "operator_yettel",
            "iliad" to "operator_iliad",
            "iliad italia" to "operator_iliad",
            "cosmote" to "operator_cosmote",
            "rakuten mobile" to "operator_rakuten",
            "rakuten" to "operator_rakuten",
            "one nz" to "operator_onenz",
            "one new zealand" to "operator_onenz",
            "ais" to "operator_ais",
            "advanced info service" to "operator_ais",
            "truemove h" to "operator_truemove",
            "truemove" to "operator_truemove",
            "true move h" to "operator_truemove",
            "true" to "operator_truemove",
            "dtac" to "operator_dtac",
            "mobifone" to "operator_mobifone",
            "mobi phone" to "operator_mobifone",
            "vinaphone" to "operator_vinaphone",
            "vina phone" to "operator_vinaphone",
            "vnpt" to "operator_vinaphone",
            "viettel" to "operator_viettel",
            "celcomdigi" to "operator_celcomdigi",
            "celcom digi" to "operator_celcomdigi",
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
            "onecall" to "operator_onecall",
            "mycall" to "operator_mycall",
            "phonero" to "operator_phonero",
            "talkmore" to "operator_talkmore",
            "chilimobil" to "operator_chilimobil",
            "fjordkraft" to "operator_fjordkraft",
            "yoigo" to "operator_yoigo",
            "euskaltel" to "operator_euskaltel",
            "telecable" to "operator_telecable",
            "simyo" to "operator_simyo",
            "jazztel" to "operator_jazztel",
            "amena" to "operator_amena",
            "masmovil" to "operator_masmovil",
            "mas movil" to "operator_masmovil",
            "pepephone" to "operator_pepephone",
            "lowi" to "operator_lowi",
            "lebara" to "operator_lebara",
            "lycamobile" to "operator_lycamobile",
            "finetwork" to "operator_finetwork",
            "r" to "operator_r",
            "ee" to "operator_ee",
        )

    /**
     * Resets the cache state completely, clearing the database map and load attempt counter.
     */
    @VisibleForTesting
    internal fun reset() {
        synchronized(this) {
            db = null
            loadAttempts = 0
        }
    }

    /**
     * Returns the cached MCC/MNC database.
     * Uses Double-Checked Locking for thread-safe initialization.
     */
    private fun loadDb(context: Context): Map<String, String> {
        db?.let { return it }
        return synchronized(this) {
            val currentDb = db
            when {
                currentDb != null -> currentDb
                loadAttempts >= MAX_DB_LOAD_ATTEMPTS -> emptyMap()
                else -> {
                    loadAttempts++
                    val loaded = loadDbInternal(context)
                    if (loadAttempts >= MAX_DB_LOAD_ATTEMPTS && loaded.isEmpty()) {
                        AppLogger.e(
                            "OperatorResolver: MCC/MNC database failed to load after $MAX_DB_LOAD_ATTEMPTS attempts",
                        )
                    }
                    if (loaded.isNotEmpty()) {
                        db = loaded
                        loaded
                    } else {
                        emptyMap()
                    }
                }
            }
        }
    }

    /**
     * Loads the MCC/MNC database from assets.
     */
    private fun loadDbInternal(context: Context): Map<String, String> =
        try {
            val jsonString =
                context.assets.open("mcc_mnc_db.json").use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
                }

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
     * Resolves operator name using a list of MCC/MNC candidates.
     */
    fun resolve(
        context: Context,
        operatorName: String,
        candidates: List<String>,
        simSlot: Int,
    ): ResolutionResult {
        val trimmedName = operatorName.trim()
        if (isValidSpecificName(trimmedName)) {
            val brandKey = getOperatorBrandKey(trimmedName)
            return ResolutionResult(trimmedName, brandKey)
        }

        val localizedContext = AppLocaleContexts.forAppLocale(context)
        val database = loadDb(localizedContext)
        val resKey = findInDatabase(database, candidates)

        val resolved =
            if (resKey != null) {
                val displayName =
                    resolveFromDbKey(localizedContext, resKey)
                        ?: fallbackResolution(localizedContext, candidates.firstOrNull().orEmpty(), simSlot)
                ResolutionResult(displayName, resKey)
            } else {
                logUnknownCandidates(candidates)
                null
            }

        return resolved
            ?: ResolutionResult(fallbackResolution(localizedContext, candidates.firstOrNull().orEmpty(), simSlot), null)
    }

    private fun findInDatabase(
        database: Map<String, String>,
        candidates: List<String>,
    ): String? {
        for (code in candidates) {
            if (code.isNotEmpty() && code != "00000") {
                val key = database[code]
                if (key != null) {
                    return key
                }
            }
        }
        return null
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
     * **Validity:** a name is considered invalid if it is null or an empty string
     * (as determined by [SimUtils.isInactiveSim]), or if it matches
     * the generic "SIM N" pattern.
     *
     * @param name Operator name to check.
     * @return True if the name is specific and can be used as is.
     */
    private fun isValidSpecificName(name: String): Boolean {
        val trimmed = name.trim()
        if (SimUtils.isInactiveSim(trimmed)) return false
        if (genericSimRegex.matches(trimmed)) return false
        if (trimmed.equals("null", ignoreCase = true)) return false
        if (trimmed.all { it.isDigit() } &&
            (trimmed.length == MIN_MCC_MNC_LENGTH || trimmed.length == MAX_MCC_MNC_LENGTH)
        ) {
            return false
        }
        return true
    }

    private const val MAX_MCC_MNC_LENGTH = 6

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

    private fun String.stripDiacritics(): String {
        val nfd = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{M}+"), "")
    }

    /**
     * Resolves the operator brand key from the given operator name.
     * Lowercases query once, eliminates redundant checks, checks exact/normalized map lookup first,
     * and checks diacritics fallback only on cache/lowercase miss.
     */
    fun getOperatorBrandKey(operatorName: String): String? {
        val normalized = operatorName.lowercase(Locale.ROOT)
        tokenToResKey[normalized]?.let { return it }

        val stripped = operatorName.stripDiacritics().lowercase(Locale.ROOT)
        if (stripped != normalized) {
            tokenToResKey[stripped]?.let { return it }
        }

        val match =
            tokenToResKey.entries
                .filter { (token, _) ->
                    operatorName.hasToken(token) || normalized.hasToken(token)
                }.maxWithOrNull(
                    compareBy<Map.Entry<String, String>> { (token, _) ->
                        token.split(" ").filter { it.isNotEmpty() }.size
                    }.thenBy { (token, _) ->
                        token.length
                    },
                )?.value

        if (match != null) return match

        if (stripped != normalized) {
            return tokenToResKey.entries
                .filter { (token, _) ->
                    stripped.hasToken(token)
                }.maxWithOrNull(
                    compareBy<Map.Entry<String, String>> { (token, _) ->
                        token.split(" ").filter { it.isNotEmpty() }.size
                    }.thenBy { (token, _) ->
                        token.length
                    },
                )?.value
        }
        return null
    }

    /**
     * Resolves the operator resource ID from the given operator name.
     * Uses strict token-based matching to prevent partial matches (e.g., "Timor" matching "TIM").
     */
    fun getOperatorResourceId(operatorName: String): Int? {
        val brandKey = getOperatorBrandKey(operatorName)
        return brandKey?.let { operatorResourceMap[it] }
    }

    /**
     * Checks if the string contains [targetToken] as an isolated token.
     *
     * **Strict API Compliance:** comparison is performed in lower case
     * ([Locale.ROOT]), guaranteeing case insensitivity of input data.
     * Tokens are extracted by splitting on non-alphanumeric characters,
     * which prevents partial matches (for example, "Timor" will not match "TIM").
     *
     * **Heuristics within reliability criteria:** split by `[^a-z0-9&-]+`
     * (or `[^a-z0-9&]+` if the token contains a hyphen) is the standard approach
     * for tokenizing operator names. Hyphen is preserved as a delimiter
     * only if the token itself contains it (for example, "t-mobile").
     */
    private fun String.hasToken(targetToken: String): Boolean {
        val normalized = lowercase(Locale.ROOT)
        val target = targetToken.lowercase(Locale.ROOT)

        if (normalized == target) return true

        return if (target.contains(" ")) {
            hasMultiWordToken(normalized, target)
        } else {
            val regex = if (target.contains("-")) tokenSplitWithDashRegex else tokenSplitWithoutDashRegex
            normalized.split(regex).filter { it.isNotEmpty() }.contains(target)
        }
    }

    private fun hasMultiWordToken(
        normalizedName: String,
        targetToken: String,
    ): Boolean {
        val targetParts = targetToken.split(" ").filter { it.isNotEmpty() }
        val tokens = normalizedName.split(tokenSplitWithoutDashRegex).filter { it.isNotEmpty() }

        if (tokens.size < targetParts.size) return false

        return (0..tokens.size - targetParts.size).any { i ->
            targetParts.indices.all { j -> tokens[i + j] == targetParts[j] }
        }
    }
}
