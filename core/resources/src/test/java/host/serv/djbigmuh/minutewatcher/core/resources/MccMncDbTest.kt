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
        validateProvenanceDocumentation(mccToMncLens, failures)

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
        mccToMncLens: Map<String, Set<Int>>,
        failures: MutableList<String>,
    ) {
        var curr: File? = File(System.getProperty("user.dir") ?: ".")
        var sourcesFile: File? = null
        while (curr != null) {
            val candidate = File(curr, "docs/operator_plmn_sources.md")
            if (candidate.exists()) {
                sourcesFile = candidate
                break
            }
            curr = curr.parentFile
        }

        if (sourcesFile == null || !sourcesFile.exists()) {
            failures.add("docs/operator_plmn_sources.md does not exist")
        } else {
            val sourcesText = sourcesFile.readText()
            for (mcc in mccToMncLens.keys) {
                if (!sourcesText.contains(mcc)) {
                    failures.add("MCC $mcc present in mcc_mnc_db.json is missing in docs/operator_plmn_sources.md")
                }
            }
        }
    }
}
