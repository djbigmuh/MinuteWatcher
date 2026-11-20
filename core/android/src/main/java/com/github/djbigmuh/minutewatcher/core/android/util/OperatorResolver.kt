package com.github.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.resources.R
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

    val operatorResourceMap = OperatorBrandDatabase.operatorResourceMap

    @VisibleForTesting
    val tokenToResKey = OperatorBrandDatabase.tokenToResKey

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
                    val appContext = context.applicationContext ?: context
                    val loaded = loadDbInternal(appContext)
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
        @Suppress("TooGenericExceptionCaught")
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
            // Operation must complete without propagating exception to ensure system stability and fault tolerance.
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
            if (brandKey != null) {
                return ResolutionResult(trimmedName, brandKey)
            }
            val database = loadDb(context)
            val resKey = findInDatabase(database, candidates)
            return ResolutionResult(trimmedName, resKey)
        }

        val localizedContext = AppLocaleContexts.forAppLocale(context)
        val database = loadDb(context)
        val resKey = findInDatabase(database, candidates)

        val resolved =
            if (resKey != null) {
                val displayName =
                    resolveFromDbKey(localizedContext, resKey)
                        ?: fallbackResolution(localizedContext, displayCandidate(candidates), simSlot)
                ResolutionResult(displayName, resKey)
            } else {
                logUnknownCandidates(candidates)
                null
            }

        return resolved
            ?: ResolutionResult(fallbackResolution(localizedContext, displayCandidate(candidates), simSlot), null)
    }

    private fun displayCandidate(candidates: List<String>): String =
        candidates.firstOrNull { it.isNotEmpty() && it != "00000" }.orEmpty()

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
    internal fun isValidSpecificName(name: String): Boolean {
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

    private fun findBestTokenMatch(candidate: String): String? =
        tokenToResKey.entries
            .filter { (token, _) -> candidate.hasToken(token) }
            .maxWithOrNull(
                compareBy<Map.Entry<String, String>> { (token, _) ->
                    token.split(" ").filter { it.isNotEmpty() }.size
                }.thenBy { (token, _) -> token.length },
            )?.value

    /**
     * Resolves the operator brand key from the given operator name.
     * Lowercases query once, checks exact/normalized map lookup first,
     * and checks diacritics fallback variant when stripped differs from normalized.
     */
    fun getOperatorBrandKey(operatorName: String): String? {
        val normalized = operatorName.lowercase(Locale.ROOT)
        val stripped = operatorName.stripDiacritics().lowercase(Locale.ROOT)
        val candidates = if (stripped == normalized) listOf(normalized) else listOf(normalized, stripped)
        return candidates.firstNotNullOfOrNull { tokenToResKey[it] }
            ?: candidates.firstNotNullOfOrNull { findBestTokenMatch(it) }
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

        return normalized == target ||
            if (target.contains(" ")) {
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

        return tokens.size >= targetParts.size &&
            (0..tokens.size - targetParts.size).any { i ->
                targetParts.indices.all { j -> tokens[i + j] == targetParts[j] }
            }
    }
}
