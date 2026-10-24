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
         * Allowed MNC lengths per MCC as per ITU-T E.212 and national numbering plan specs.
         */
        private val MCC_TO_ALLOWED_MNC_LENGTHS: Map<String, Set<Int>> =
            mapOf(
                "202" to setOf(2),
                "204" to setOf(2),
                "206" to setOf(2),
                "208" to setOf(2),
                "212" to setOf(2),
                "213" to setOf(2),
                "214" to setOf(2),
                "216" to setOf(2),
                "218" to setOf(2),
                "219" to setOf(2),
                "220" to setOf(2),
                "221" to setOf(2),
                "222" to setOf(2),
                "226" to setOf(2),
                "228" to setOf(2),
                "230" to setOf(2),
                "231" to setOf(2),
                "232" to setOf(2),
                "234" to setOf(2),
                "238" to setOf(2),
                "240" to setOf(2),
                "242" to setOf(2),
                "244" to setOf(2),
                "246" to setOf(2),
                "247" to setOf(2),
                "248" to setOf(2),
                "250" to setOf(2),
                "255" to setOf(2),
                "257" to setOf(2),
                "259" to setOf(2),
                "260" to setOf(2),
                "262" to setOf(2),
                "266" to setOf(2),
                "268" to setOf(2),
                "270" to setOf(2),
                "272" to setOf(2),
                "274" to setOf(2),
                "276" to setOf(2),
                "278" to setOf(2),
                "280" to setOf(2),
                "282" to setOf(2),
                "283" to setOf(2),
                "284" to setOf(2),
                "286" to setOf(2),
                "288" to setOf(2),
                "290" to setOf(2),
                "292" to setOf(2),
                "293" to setOf(2),
                "294" to setOf(2),
                "295" to setOf(2),
                "297" to setOf(2),
                "302" to setOf(3),
                "308" to setOf(2),
                "310" to setOf(3),
                "311" to setOf(3),
                "312" to setOf(3),
                "313" to setOf(3),
                "330" to setOf(3),
                "334" to setOf(3),
                "338" to setOf(3),
                "342" to setOf(3),
                "344" to setOf(3),
                "346" to setOf(3),
                "348" to setOf(3),
                "352" to setOf(3),
                "354" to setOf(3),
                "358" to setOf(3),
                "360" to setOf(3),
                "362" to setOf(2),
                "363" to setOf(2),
                "365" to setOf(3),
                "366" to setOf(3),
                "368" to setOf(2),
                "370" to setOf(2),
                "372" to setOf(2),
                "374" to setOf(2),
                "376" to setOf(3),
                "400" to setOf(2),
                "401" to setOf(2),
                "402" to setOf(2),
                "404" to setOf(2),
                "405" to setOf(2, 3),
                "410" to setOf(2),
                "412" to setOf(2),
                "413" to setOf(2),
                "414" to setOf(2),
                "415" to setOf(2),
                "416" to setOf(2),
                "417" to setOf(2),
                "418" to setOf(2),
                "419" to setOf(2),
                "420" to setOf(2),
                "421" to setOf(2),
                "422" to setOf(2),
                "424" to setOf(2),
                "425" to setOf(2),
                "426" to setOf(2),
                "427" to setOf(2),
                "428" to setOf(2),
                "429" to setOf(2),
                "430" to setOf(2),
                "431" to setOf(2),
                "432" to setOf(2),
                "434" to setOf(2),
                "436" to setOf(2),
                "437" to setOf(2),
                "438" to setOf(2),
                "440" to setOf(2),
                "441" to setOf(2),
                "450" to setOf(2),
                "452" to setOf(2),
                "454" to setOf(2),
                "455" to setOf(2),
                "456" to setOf(2),
                "457" to setOf(2),
                "460" to setOf(2),
                "467" to setOf(2),
                "466" to setOf(2),
                "470" to setOf(2),
                "472" to setOf(2),
                "502" to setOf(2, 3),
                "505" to setOf(2),
                "510" to setOf(2),
                "514" to setOf(2),
                "515" to setOf(2),
                "520" to setOf(2),
                "525" to setOf(2),
                "528" to setOf(2),
                "530" to setOf(2),
                "536" to setOf(2),
                "537" to setOf(2),
                "539" to setOf(2),
                "540" to setOf(2),
                "541" to setOf(2),
                "542" to setOf(2),
                "543" to setOf(2),
                "544" to setOf(2),
                "545" to setOf(2),
                "546" to setOf(2),
                "547" to setOf(2),
                "548" to setOf(2),
                "549" to setOf(2),
                "550" to setOf(2),
                "340" to setOf(2),
                "350" to setOf(3),
                "356" to setOf(3),
                "364" to setOf(2),
                "702" to setOf(2),
                "738" to setOf(2),
                "742" to setOf(2),
                "746" to setOf(2),
                "750" to setOf(3),
                "551" to setOf(2),
                "552" to setOf(2),
                "553" to setOf(2),
                "554" to setOf(2),
                "555" to setOf(2),
                "602" to setOf(2),
                "603" to setOf(2),
                "604" to setOf(2),
                "605" to setOf(2),
                "606" to setOf(2),
                "607" to setOf(2),
                "608" to setOf(2),
                "609" to setOf(2),
                "610" to setOf(2),
                "611" to setOf(2),
                "612" to setOf(2),
                "613" to setOf(2),
                "614" to setOf(2),
                "615" to setOf(2),
                "616" to setOf(2),
                "617" to setOf(2),
                "618" to setOf(2),
                "619" to setOf(2),
                "620" to setOf(2),
                "621" to setOf(2),
                "622" to setOf(2),
                "623" to setOf(2),
                "624" to setOf(2),
                "625" to setOf(2),
                "626" to setOf(2),
                "627" to setOf(2),
                "628" to setOf(2),
                "629" to setOf(2),
                "630" to setOf(2),
                "631" to setOf(2),
                "632" to setOf(2),
                "633" to setOf(2),
                "634" to setOf(2),
                "635" to setOf(2),
                "636" to setOf(2),
                "637" to setOf(2),
                "638" to setOf(2),
                "639" to setOf(2),
                "640" to setOf(2),
                "641" to setOf(2),
                "642" to setOf(2),
                "643" to setOf(2),
                "645" to setOf(2),
                "646" to setOf(2),
                "647" to setOf(2),
                "648" to setOf(2),
                "649" to setOf(2),
                "650" to setOf(2),
                "651" to setOf(2),
                "652" to setOf(2),
                "653" to setOf(2),
                "654" to setOf(2),
                "655" to setOf(2),
                "657" to setOf(2),
                "658" to setOf(2),
                "659" to setOf(2),
                "704" to setOf(2),
                "706" to setOf(2),
                "708" to setOf(3),
                "710" to setOf(2),
                "712" to setOf(2),
                "714" to setOf(2, 3),
                "716" to setOf(2),
                "722" to setOf(3),
                "724" to setOf(2),
                "730" to setOf(2),
                "732" to setOf(3),
                "734" to setOf(2),
                "736" to setOf(2),
                "740" to setOf(2),
                "744" to setOf(2),
                "748" to setOf(2),
            )

        /**
         * Whitelisted MCCs allowed to have consecutive MNC numerical sequences > 6 due to historical blocks.
         */
        private val CONSECUTIVE_MNC_WHITELIST =
            setOf(
                "214",
                "231",
                "232",
                "262",
                "311",
                "404",
                "405",
                "454",
                "502",
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
        validateMncLengthsAgainstTable(mccToMncLens, failures)
        validateNoZeroPaddedDuplicates(plmns, failures)
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

    private fun validateMncLengthsAgainstTable(
        mccToMncLens: Map<String, Set<Int>>,
        failures: MutableList<String>,
    ) {
        for ((mcc, lens) in mccToMncLens) {
            val allowedLens = MCC_TO_ALLOWED_MNC_LENGTHS[mcc]
            if (allowedLens == null) {
                failures.add("MCC $mcc is missing in MCC_TO_ALLOWED_MNC_LENGTHS table")
            } else {
                for (len in lens) {
                    if (len !in allowedLens) {
                        failures.add("MCC $mcc has MNC length $len which is not in allowed lengths $allowedLens")
                    }
                }
            }
        }
    }

    private fun validateNoZeroPaddedDuplicates(
        plmns: List<String>,
        failures: MutableList<String>,
    ) {
        val plmnSet = plmns.toSet()
        for (plmn in plmns) {
            val mcc = plmn.substring(0, 3)
            val mncStr = plmn.substring(3)

            // Check 2-digit vs 3-digit zero padding ambiguity (e.g. 70801 vs 708001)
            if (mncStr.length == 2 && mncStr.startsWith("0")) {
                val padded3 = mcc + "0" + mncStr
                if (padded3 in plmnSet) {
                    failures.add("Zero-padded duplicate pair found: $plmn and $padded3")
                }
            } else if (mncStr.length == 3 && mncStr.startsWith("0")) {
                val unpadded2 = mcc + mncStr.substring(1)
                if (unpadded2 in plmnSet) {
                    failures.add("Zero-padded duplicate pair found: $plmn and $unpadded2")
                }
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
                failures.add("MCC $mcc is present in database (JSON) but missing in docs/operator_plmn_sources.md")
            } else if (jsonPairs == null && docPairs != null) {
                failures.add("MCC $mcc is present in docs/operator_plmn_sources.md but missing in database (JSON)")
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
