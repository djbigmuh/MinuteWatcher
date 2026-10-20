package host.serv.djbigmuh.minutewatcher.core.resources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.android.util.OperatorResolver
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MccMncDbTest {
    private companion object {
        /**
         * MCCs where ITU-T E.212 / national numbering plans allow mixed MNC lengths (2 and 3 digits).
         */
        private val MIXED_MNC_LENGTH_MCCS =
            setOf(
                "334",
                "405",
            )

        /**
         * Whitelisted MCCs allowed to have consecutive MNC numerical sequences > 6 due to historical blocks (e.g. US, India, Latin America).
         */
        private val CONSECUTIVE_MNC_WHITELIST =
            setOf(
                "214",
                "262",
                "311",
                "404",
                "405",
                "704",
                "706",
                "708",
                "710",
                "712",
                "714",
                "716",
                "722",
                "724",
                "730",
                "732",
                "734",
                "736",
                "740",
                "744",
                "748",
            )
    }

    @Test
    fun `verify mcc mnc database correctness and documentation provenance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assetManager = context.assets
        val jsonText = assetManager.open("mcc_mnc_db.json").bufferedReader().use { it.readText() }
        val jsonObj = JSONObject(jsonText)

        val keysIterator = jsonObj.keys()
        val plmns = mutableListOf<String>()
        while (keysIterator.hasNext()) {
            plmns.add(keysIterator.next())
        }

        val failures = mutableListOf<String>()

        val sortedPlmns = plmns.sorted()
        if (plmns != sortedPlmns) {
            failures.add("mcc_mnc_db.json keys are not sorted in ascending order.")
        }

        val operatorResKeys = OperatorResolver.operatorResourceMap.keys
        val mccToMncLens = mutableMapOf<String, MutableSet<Int>>()
        val mccToMncs = mutableMapOf<String, MutableList<Int>>()

        validateEntries(plmns, jsonObj, operatorResKeys, mccToMncLens, mccToMncs, failures)
        validateMncLengthUniformity(mccToMncLens, failures)
        validateConsecutiveSequences(mccToMncs, failures)
        validateProvenanceDocumentation(jsonObj, failures)

        if (failures.isNotEmpty()) {
            fail("MCC-MNC DB validation failures:\n" + failures.joinToString("\n"))
        }
    }

    private fun validateEntries(
        plmns: List<String>,
        jsonObj: JSONObject,
        operatorResKeys: Set<String>,
        mccToMncLens: MutableMap<String, MutableSet<Int>>,
        mccToMncs: MutableMap<String, MutableList<Int>>,
        failures: MutableList<String>,
    ) {
        val seenKeys = mutableSetOf<String>()
        for (plmn in plmns) {
            if (!seenKeys.add(plmn)) {
                failures.add("Duplicate PLMN key: $plmn")
            }
            if (plmn.length !in 5..6 || !plmn.all { it.isDigit() }) {
                failures.add("Invalid PLMN format (must be 5-6 digits): $plmn")
            }
            val mcc = plmn.substring(0, 3)
            val mncStr = plmn.substring(3)
            val mncInt = mncStr.toIntOrNull() ?: -1

            mccToMncLens.getOrPut(mcc) { mutableSetOf() }.add(mncStr.length)
            mccToMncs.getOrPut(mcc) { mutableListOf() }.add(mncInt)

            val value = jsonObj.getString(plmn)
            if (value !in operatorResKeys) {
                failures.add("PLMN $plmn has unmapped operator value '$value'")
            }
            if (value != value.trim() || value != value.lowercase()) {
                failures.add("Operator value '$value' for PLMN $plmn contains whitespace or uppercase letters")
            }
        }
    }

    private fun validateMncLengthUniformity(
        mccToMncLens: Map<String, Set<Int>>,
        failures: MutableList<String>,
    ) {
        for ((mcc, lens) in mccToMncLens) {
            if (lens.size > 1 && mcc !in MIXED_MNC_LENGTH_MCCS) {
                failures.add("MCC $mcc has mixed MNC lengths ($lens) but is not in MIXED_MNC_LENGTH_MCCS whitelist")
            }
        }
    }

    private fun validateConsecutiveSequences(
        mccToMncs: Map<String, List<Int>>,
        failures: MutableList<String>,
    ) {
        for ((mcc, mncs) in mccToMncs) {
            val sortedMncs = mncs.sorted()
            if (mcc !in CONSECUTIVE_MNC_WHITELIST && sortedMncs.isNotEmpty()) {
                var seq = 1
                var maxSeq = 1
                for (i in 1 until sortedMncs.size) {
                    if (sortedMncs[i] == sortedMncs[i - 1] + 1) {
                        seq++
                        maxSeq = maxOf(maxSeq, seq)
                    } else {
                        seq = 1
                    }
                }
                if (maxSeq > 6) {
                    failures.add("MCC $mcc has consecutive MNC sequence of length $maxSeq without being whitelisted")
                }
            }
        }
    }

    private fun validateProvenanceDocumentation(
        jsonObj: JSONObject,
        failures: MutableList<String>,
    ) {
        val sourcesFile = findSourcesFile()
        if (sourcesFile == null || !sourcesFile.exists()) {
            failures.add("docs/operator_plmn_sources.md does not exist")
            return
        }

        val jsonMap = parseJsonMap(jsonObj)
        val docMap = parseDocMap(sourcesFile)
        compareMaps(jsonMap, docMap, failures)
    }

    private fun findSourcesFile(): File? {
        var curr: File? = File(System.getProperty("user.dir") ?: ".")
        while (curr != null) {
            val candidate = File(curr, "docs/operator_plmn_sources.md")
            if (candidate.exists()) {
                return candidate
            }
            curr = curr.parentFile
        }
        return null
    }

    private fun parseJsonMap(jsonObj: JSONObject): Map<String, Map<String, String>> {
        val jsonMap = mutableMapOf<String, MutableMap<String, String>>()
        val keysIterator = jsonObj.keys()
        while (keysIterator.hasNext()) {
            val plmn = keysIterator.next()
            val brand = jsonObj.getString(plmn)
            val mcc = plmn.substring(0, 3)
            jsonMap.getOrPut(mcc) { mutableMapOf() }[plmn] = brand
        }
        return jsonMap
    }

    private fun parseDocMap(sourcesFile: File): Map<String, Map<String, String>> {
        val docMap = mutableMapOf<String, MutableMap<String, String>>()
        val pairRegex = Regex("""(\d{5,6})\s*=\s*(operator_[a-z0-9_]+)""")

        sourcesFile.forEachLine { line ->
            if (line.startsWith("|")) {
                parseDocLine(line, pairRegex, docMap)
            }
        }
        return docMap
    }

    private fun parseDocLine(
        line: String,
        pairRegex: Regex,
        docMap: MutableMap<String, MutableMap<String, String>>,
    ) {
        val parts = line.split("|").map { it.trim() }
        if (parts.size >= 4) {
            val mccCandidate = parts[1]
            if (mccCandidate.length == 3 && mccCandidate.all { it.isDigit() }) {
                val plmnCell = parts[3]
                val pairs = docMap.getOrPut(mccCandidate) { mutableMapOf() }
                pairRegex.findAll(plmnCell).forEach { match ->
                    val plmn = match.groupValues[1]
                    val brand = match.groupValues[2]
                    pairs[plmn] = brand
                }
            }
        }
    }

    private fun compareMaps(
        jsonMap: Map<String, Map<String, String>>,
        docMap: Map<String, Map<String, String>>,
        failures: MutableList<String>,
    ) {
        val allMccs = jsonMap.keys + docMap.keys
        for (mcc in allMccs.sorted()) {
            val jsonPairs = jsonMap[mcc]
            val docPairs = docMap[mcc]

            if (jsonPairs != null && docPairs == null) {
                failures.add("MCC $mcc is present in JSON but missing in documentation table")
            } else if (jsonPairs == null && docPairs != null) {
                failures.add("MCC $mcc is present in documentation table but missing in JSON")
            } else if (jsonPairs != null && docPairs != null) {
                comparePlmnPairs(mcc, jsonPairs, docPairs, failures)
            }
        }
    }

    private fun comparePlmnPairs(
        mcc: String,
        jsonPairs: Map<String, String>,
        docPairs: Map<String, String>,
        failures: MutableList<String>,
    ) {
        val allPlmns = jsonPairs.keys + docPairs.keys
        for (plmn in allPlmns.sorted()) {
            val jsonBrand = jsonPairs[plmn]
            val docBrand = docPairs[plmn]

            if (jsonBrand != null && docBrand == null) {
                failures.add(
                    "PLMN $plmn=$jsonBrand is present in JSON but missing in " +
                        "documentation row for MCC $mcc",
                )
            } else if (jsonBrand == null && docBrand != null) {
                failures.add(
                    "PLMN $plmn=$docBrand is present in documentation row for " +
                        "MCC $mcc but missing in JSON",
                )
            } else if (jsonBrand != docBrand) {
                failures.add(
                    "PLMN $plmn brand mismatch for MCC $mcc: " +
                        "JSON has '$jsonBrand', documentation has '$docBrand'",
                )
            }
        }
    }
}
