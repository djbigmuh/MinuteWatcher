package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OperatorBrandSetConsistencyTest {
    @Test
    fun `operator brand sets stay consistent across resources and database`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resDirProp = System.getProperty("minutewatcher.coreResourcesResDir") ?: "core/resources/src/main/res"
        val resDir = File(resDirProp)
        val drawableDir = File(resDir, "drawable")
        val drawableFiles =
            drawableDir.listFiles()?.filter {
                it.name.startsWith("ic_operator_") && it.name.endsWith(".xml")
            } ?: emptyList()
        assertTrue("Operator drawable files must exist", drawableFiles.isNotEmpty())

        val iconMapResIds = OperatorBrandDatabase.operatorIconMap.values.toSet()
        val iconMapResNames =
            OperatorBrandDatabase.operatorIconMap
                .map { (resId, iconId) ->
                    context.resources.getResourceEntryName(resId) to
                        context.resources.getResourceEntryName(iconId)
                }.toMap()

        val failures = mutableListOf<String>()

        checkDrawableConsistency(drawableDir, drawableFiles, iconMapResNames, iconMapResIds, context, failures)

        val operatorResKeys = OperatorResolver.operatorResourceMap.keys
        val tokenValues = OperatorResolver.tokenToResKey.values.toSet()
        val iconMapStringKeys =
            OperatorBrandDatabase.operatorIconMap.keys
                .map { context.resources.getResourceEntryName(it) }
                .toSet()

        val dbValues = checkDatabaseConsistency(context, operatorResKeys, tokenValues, failures)
        checkOperatorResourceConsistency(operatorResKeys, tokenValues, dbValues, iconMapStringKeys, failures)
        checkDonotTranslateConsistency(resDir, operatorResKeys)

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
                    "Drawable file '$nameWithoutExt.xml' is not mapped in OperatorBrandDatabase.operatorIconMap",
                )
            }
        }

        for (iconResId in iconMapResIds) {
            val entryName = context.resources.getResourceEntryName(iconResId)
            val expectedFile = File(drawableDir, "$entryName.xml")
            if (!expectedFile.exists()) {
                failures.add(
                    "Icon resource '$entryName' in OperatorBrandDatabase.operatorIconMap does not have a corresponding file",
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
                    "Operator key '$key' in operatorResourceMap is missing in OperatorBrandDatabase.operatorIconMap keys",
                )
            }
        }
    }

    private fun checkDatabaseConsistency(
        context: Context,
        operatorResKeys: Set<String>,
        tokenValues: Set<String>,
        failures: MutableList<String>,
    ): Set<String> {
        val jsonText =
            context.assets
                .open("mcc_mnc_db.json")
                .bufferedReader()
                .use { it.readText() }
        val jsonObj = org.json.JSONObject(jsonText)
        val dbValues = mutableSetOf<String>()
        val keysIterator = jsonObj.keys()

        while (keysIterator.hasNext()) {
            val plmn = keysIterator.next()
            val value = jsonObj.getString(plmn)
            dbValues.add(value)
            if (value !in operatorResKeys) {
                failures.add("mcc_mnc_db.json entry $plmn references unmapped operator '$value'")
            }
        }

        for (tokenVal in tokenValues) {
            if (tokenVal !in operatorResKeys) {
                failures.add("tokenToResKey value '$tokenVal' is not mapped in operatorResourceMap")
            }
        }

        return dbValues
    }

    private fun checkDonotTranslateConsistency(
        resDir: File,
        operatorResKeys: Set<String>,
    ) {
        val valuesDir = File(resDir, "values")
        val donottranslateFile = File(valuesDir, "donottranslate.xml")
        assertTrue("donottranslate.xml must exist at $donottranslateFile", donottranslateFile.exists())

        val factory =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(donottranslateFile)
        val stringNodes = doc.getElementsByTagName("string")
        val declaredNames = mutableSetOf<String>()

        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val element = node as org.w3c.dom.Element
            val name = element.getAttribute("name")
            if (name.startsWith("operator_")) {
                declaredNames.add(name)
            }
        }

        for (key in operatorResKeys) {
            assertTrue("Operator key '$key' must be declared in donottranslate.xml", key in declaredNames)
        }
    }
}
