package com.github.djbigmuh.minutewatcher

import android.icu.text.PluralRules
import com.github.djbigmuh.minutewatcher.core.resources.testing.LocaleResourceDirs
import com.github.djbigmuh.minutewatcher.core.resources.testing.ProjectPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
class PluralResourcesTest {
    private val locales = LocaleResourceDirs.supportedLanguageTags

    @Test
    fun `plural resources cover required cardinal categories for all locales`() {
        val testValues = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 5.0, 11.0, 100.0, 1000000.0, 1.5)
        val resDir = findResDir()

        locales.forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            val pluralRules = PluralRules.forLocale(locale) ?: error("PluralRules for $tag must not be null")
            val requiredCategories = testValues.map { pluralRules.select(it) }.toSet()

            val dirName = LocaleResourceDirs.resourceDirForTag(tag)
            val file = File(resDir, "$dirName/strings.xml")
            assertTrue("strings.xml for locale $tag must exist", file.exists())

            val minutesCategories = parsePluralItems(file, "minutes_plurals").keys
            val daysCategories = parsePluralItems(file, "days_plurals").keys

            val minError = "minutes_plurals in locale $tag must cover $requiredCategories, got $minutesCategories"
            assertTrue(minError, minutesCategories.containsAll(requiredCategories))

            val dayError = "days_plurals in locale $tag must cover $requiredCategories, got $daysCategories"
            assertTrue(dayError, daysCategories.containsAll(requiredCategories))
        }
    }

    @Test
    fun `plural items contain exactly one format argument except ar one and two`() {
        val resDir = findResDir()

        locales.forEach { tag ->
            val dirName = LocaleResourceDirs.resourceDirForTag(tag)
            val file = File(resDir, "$dirName/strings.xml")

            val minutesItems = parsePluralItems(file, "minutes_plurals")
            val daysItems = parsePluralItems(file, "days_plurals")

            verifyFormatArguments(tag, "minutes_plurals", minutesItems)
            verifyFormatArguments(tag, "days_plurals", daysItems)
        }
    }

    @Test
    fun `strings xml files do not contain MissingQuantity attribute`() {
        val resDir = findResDir()

        locales.forEach { lang ->
            val dirName = LocaleResourceDirs.resourceDirForTag(lang)
            val file = File(resDir, "$dirName/strings.xml")
            if (file.exists()) {
                val content = file.readText()
                assertFalse(
                    "strings.xml in $dirName must not contain MissingQuantity",
                    content.contains("MissingQuantity"),
                )
            }
        }
    }

    @Test
    fun `many category for es and fr match expected specification strings`() {
        val resDir = findResDir()

        val esFile = File(resDir, "values-es/strings.xml")
        val esMinutesItems = parsePluralItems(esFile, "minutes_plurals")
        val esDaysItems = parsePluralItems(esFile, "days_plurals")

        assertEquals("%1\$d de minutos", esMinutesItems["many"])
        assertEquals("%1\$d de días", esDaysItems["many"])

        val frFile = File(resDir, "values-fr/strings.xml")
        val frMinutesItems = parsePluralItems(frFile, "minutes_plurals")
        val frDaysItems = parsePluralItems(frFile, "days_plurals")

        assertEquals("%1\$d de minutes", frMinutesItems["many"])
        assertEquals("%1\$d de jours", frDaysItems["many"])
    }

    @Test
    fun `urdu plurals have one and other categories containing format argument`() {
        val resDir = findResDir()
        val urFile = File(resDir, "values-ur/strings.xml")
        val urMinutes = parsePluralItems(urFile, "minutes_plurals")
        val urDays = parsePluralItems(urFile, "days_plurals")

        assertTrue(urMinutes.containsKey("one"))
        assertTrue(urMinutes.containsKey("other"))
        assertTrue(urMinutes["one"]!!.contains("%1\$d"))
        assertTrue(urMinutes["other"]!!.contains("%1\$d"))

        assertTrue(urDays.containsKey("one"))
        assertTrue(urDays.containsKey("other"))
        assertTrue(urDays["one"]!!.contains("%1\$d"))
        assertTrue(urDays["other"]!!.contains("%1\$d"))
    }

    @Test
    fun `plurals with many quantity have tools ignore UnusedQuantity`() {
        val resDir = findResDir()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()

        locales.forEach { tag ->
            val dirName = LocaleResourceDirs.resourceDirForTag(tag)
            val file = File(resDir, "$dirName/strings.xml")
            if (file.exists()) {
                val doc = builder.parse(file)
                val plurals = doc.getElementsByTagName("plurals")
                checkPluralsHaveUnusedQuantityIgnore(plurals, dirName)
            }
        }
    }

    private fun checkPluralsHaveUnusedQuantityIgnore(
        plurals: org.w3c.dom.NodeList,
        dirName: String,
    ) {
        for (i in 0 until plurals.length) {
            val node = plurals.item(i) as org.w3c.dom.Element
            if (hasManyQuantity(node)) {
                val ignore = node.getAttribute("tools:ignore")
                assertTrue(
                    "Plural element '${
                        node.getAttribute(
                            "name",
                        )
                    }' in $dirName must have tools:ignore=\"UnusedQuantity\"",
                    ignore.contains("UnusedQuantity"),
                )
            }
        }
    }

    private fun hasManyQuantity(node: org.w3c.dom.Element): Boolean {
        val items = node.getElementsByTagName("item")
        for (j in 0 until items.length) {
            val item = items.item(j) as org.w3c.dom.Element
            if (item.getAttribute("quantity") == "many") {
                return true
            }
        }
        return false
    }

    private fun verifyFormatArguments(
        tag: String,
        resourceName: String,
        items: Map<String, String>,
    ) {
        items.forEach { (quantity, value) ->
            if (tag == "ar" && (quantity == "one" || quantity == "two")) {
                assertFalse(
                    "Arabic $quantity item in $resourceName should not contain %1\$d, was: $value",
                    value.contains("%1\$d"),
                )
            } else {
                assertTrue(
                    "$resourceName item for quantity $quantity in locale $tag must contain %1\$d, was: $value",
                    value.contains("%1\$d"),
                )
            }
        }
    }

    private fun parsePluralItems(
        file: File,
        pluralName: String,
    ): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val pluralsList = doc.getElementsByTagName("plurals")
        val result = mutableMapOf<String, String>()

        for (i in 0 until pluralsList.length) {
            val node = pluralsList.item(i)
            if (node.attributes?.getNamedItem("name")?.nodeValue == pluralName) {
                result.putAll(extractItemsFromPluralNode(node))
            }
        }
        return result
    }

    private fun extractItemsFromPluralNode(node: Node): Map<String, String> {
        val itemsMap = mutableMapOf<String, String>()
        val items = node.childNodes
        for (j in 0 until items.length) {
            val itemNode = items.item(j)
            if (itemNode.nodeName == "item") {
                val quantity = itemNode.attributes?.getNamedItem("quantity")?.nodeValue
                val value = itemNode.textContent?.trim()
                if (quantity != null && value != null) {
                    itemsMap[quantity] = value
                }
            }
        }
        return itemsMap
    }

    private fun findResDir(): File = ProjectPaths.resDir
}
