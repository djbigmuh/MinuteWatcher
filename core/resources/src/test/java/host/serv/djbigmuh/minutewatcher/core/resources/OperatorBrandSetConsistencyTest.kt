package host.serv.djbigmuh.minutewatcher.core.resources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.android.util.OperatorResolver
import host.serv.djbigmuh.minutewatcher.core.android.util.SimUtils
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class OperatorBrandSetConsistencyTest {
    @Test
    fun `operator brand sets stay consistent across resources and database`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resDirProp = System.getProperty("minutewatcher.coreResourcesResDir") ?: "src/main/res"
        val resDir = File(resDirProp)
        val drawableDir = File(resDir, "drawable")
        val drawableFiles =
            drawableDir.listFiles()?.filter {
                it.name.startsWith("ic_operator_") && it.name.endsWith(".xml")
            } ?: emptyList()
        assertTrue("Operator drawable files must exist", drawableFiles.isNotEmpty())

        val iconMapResIds = SimUtils.operatorIconMap.values.toSet()
        val iconMapResNames =
            SimUtils.operatorIconMap
                .map { (resId, iconId) ->
                    context.resources.getResourceEntryName(resId) to
                        context.resources.getResourceEntryName(iconId)
                }.toMap()

        val failures = mutableListOf<String>()

        checkDrawableConsistency(drawableDir, drawableFiles, iconMapResNames, iconMapResIds, context, failures)

        val operatorResKeys = OperatorResolver.operatorResourceMap.keys
        val tokenValues = OperatorResolver.tokenToResKey.values.toSet()
        val iconMapStringKeys =
            SimUtils.operatorIconMap.keys
                .map { context.resources.getResourceEntryName(it) }
                .toSet()

        val dbValues = checkDatabaseConsistency(context, operatorResKeys, tokenValues, failures)
        checkOperatorResourceConsistency(operatorResKeys, tokenValues, dbValues, iconMapStringKeys, failures)
        checkDonotTranslateConsistency(resDir, operatorResKeys, failures)

        if (failures.isNotEmpty()) {
            fail("Operator brand set consistency failures:\n" + failures.joinToString("\n"))
        }
    }

    private fun checkDrawableConsistency(
        drawableDir: File,
        drawableFiles: List<File>,
        iconMapResNames: Map<String, String>,
        iconMapResIds: Set<Int>,
        context: Context,
        failures: MutableList<String>,
    ) {
        for (file in drawableFiles) {
            val nameWithoutExt = file.nameWithoutExtension
            if (!iconMapResNames.containsValue(nameWithoutExt)) {
                failures.add(
                    "Drawable file '$nameWithoutExt.xml' is not mapped in SimUtils.operatorIconMap",
                )
            }
        }

        for (iconResId in iconMapResIds) {
            val entryName = context.resources.getResourceEntryName(iconResId)
            val expectedFile = File(drawableDir, "$entryName.xml")
            if (!expectedFile.exists()) {
                failures.add(
                    "Icon resource '$entryName' in SimUtils.operatorIconMap does not have a corresponding file",
                )
            }
        }
    }

    private fun checkOperatorResourceConsistency(
        operatorResKeys: Set<String>,
        tokenValues: Set<String>,
        dbValues: Set<String>,
        iconMapStringKeys: Set<String>,
        failures: MutableList<String>,
    ) {
        for (key in operatorResKeys) {
            if (key !in tokenValues && key !in dbValues) {
                failures.add(
                    "Operator key '$key' in operatorResourceMap is missing in tokenToResKey values and mcc_mnc_db.json",
                )
            }
            if (key !in iconMapStringKeys) {
                failures.add(
                    "Operator key '$key' in operatorResourceMap is missing in SimUtils.operatorIconMap keys",
                )
            }
        }
    }

    private fun checkDonotTranslateConsistency(
        resDir: File,
        operatorResKeys: Set<String>,
        failures: MutableList<String>,
    ) {
        val dontTranslateFile = File(resDir, "values/donottranslate.xml")
        assertTrue("donottranslate.xml must exist", dontTranslateFile.exists())
        val dbFactory = DocumentBuilderFactory.newInstance()
        val doc = dbFactory.newDocumentBuilder().parse(dontTranslateFile)
        val stringNodes = doc.getElementsByTagName("string")
        val donotTranslateNames = mutableSetOf<String>()
        for (i in 0 until stringNodes.length) {
            val elem = stringNodes.item(i) as Element
            donotTranslateNames.add(elem.getAttribute("name"))
        }

        for (key in operatorResKeys) {
            if (key !in donotTranslateNames) {
                failures.add("Operator key '$key' is missing in donottranslate.xml")
            }
        }
    }

    private fun checkDatabaseConsistency(
        context: Context,
        operatorResKeys: Set<String>,
        tokenValues: Set<String>,
        failures: MutableList<String>,
    ): Set<String> {
        val assetManager = context.assets
        val jsonStream = assetManager.open("mcc_mnc_db.json")
        val jsonText = jsonStream.bufferedReader().use { it.readText() }
        val jsonObj = JSONObject(jsonText)

        val keysSeen = mutableSetOf<String>()
        val dbValues = mutableSetOf<String>()

        val keysIterator = jsonObj.keys()
        while (keysIterator.hasNext()) {
            val plmn = keysIterator.next()
            if (plmn in keysSeen) {
                failures.add("Duplicate PLMN key in mcc_mnc_db.json: '$plmn'")
            }
            keysSeen.add(plmn)

            if (plmn.length !in 5..6 || !plmn.all { it.isDigit() }) {
                failures.add("Invalid PLMN key in mcc_mnc_db.json: '$plmn' (must be 5-6 digits)")
            }

            val operatorKey = jsonObj.getString(plmn)
            dbValues.add(operatorKey)

            if (operatorKey !in operatorResKeys) {
                failures.add(
                    "Value '$operatorKey' in mcc_mnc_db.json for PLMN '$plmn' is not in OperatorResolver.operatorResourceMap",
                )
            }
        }

        for (key in operatorResKeys) {
            val inDb = key in dbValues
            val inToken = key in tokenValues
            if (!inDb && !inToken) {
                failures.add(
                    "Operator key '$key' has no entries in mcc_mnc_db.json and no tokens in tokenToResKey",
                )
            }
        }

        return dbValues
    }
}
