package com.example.minutewatcher.core.resources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.android.util.OperatorResolver
import com.example.minutewatcher.core.android.util.SimUtils
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
    fun `test operator brand set consistency across drawable assets, operatorIconMap, operatorResourceMap, tokenToResKey, donottranslate, and mcc_mnc_db`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val resDirProp = System.getProperty("minutewatcher.coreResourcesResDir") ?: "src/main/res"
        val resDir = File(resDirProp)
        val drawableDir = File(resDir, "drawable")
        val drawableFiles =
            drawableDir.listFiles()?.filter { it.name.startsWith("ic_operator_") && it.name.endsWith(".xml") }
                ?: emptyList()
        assertTrue("Operator drawable files must exist", drawableFiles.isNotEmpty())

        val iconMapResIds = SimUtils.operatorIconMap.values.toSet()
        val iconMapResNames =
            SimUtils.operatorIconMap
                .map { (resId, iconId) ->
                    context.resources.getResourceEntryName(resId) to context.resources.getResourceEntryName(iconId)
                }.toMap()

        val failures = mutableListOf<String>()

        for (file in drawableFiles) {
            val nameWithoutExt = file.nameWithoutExtension
            if (!iconMapResNames.containsValue(nameWithoutExt)) {
                failures.add("Drawable file '$nameWithoutExt.xml' is not mapped in SimUtils.operatorIconMap")
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

        val operatorResKeys = OperatorResolver.operatorResourceMap.keys
        val tokenValues = OperatorResolver.tokenToResKey.values.toSet()
        val iconMapStringKeys =
            SimUtils.operatorIconMap.keys
                .map { context.resources.getResourceEntryName(it) }
                .toSet()

        for (key in operatorResKeys) {
            if (key !in tokenValues) {
                failures.add(
                    "Operator key '$key' in operatorResourceMap is missing in OperatorResolver.tokenToResKey values",
                )
            }
            if (key !in iconMapStringKeys) {
                failures.add("Operator key '$key' in operatorResourceMap is missing in SimUtils.operatorIconMap keys")
            }
        }

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
                failures.add("Operator key '$key' has no entries in mcc_mnc_db.json and no tokens in tokenToResKey")
            }
        }

        if (failures.isNotEmpty()) {
            fail("Operator brand set consistency failures:\n" + failures.joinToString("\n"))
        }
    }
}
