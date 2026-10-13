package com.example.minutewatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UrduLocalizationTest {
    private val allowedExceptionKeys =
        setOf(
            "gb_abbr", // International unit abbreviation "GB"
            "unit_gigabytes", // International unit abbreviation "GB"
            "check_mark", // Symbol "✓"
            "shortcut_minutes_short", // Structural format template "%1$d %2$s"
            "shortcut_traffic_short", // Structural format template "%1$.1f %2$s"
            "shortcut_long_format", // Structural format template "%1$s: %2$s %3$s"
        )

    @Test
    fun `urdu strings in xml are localized and differ from default english strings except allowed exceptions`() {
        val resDir = findResDir()
        val defaultFile = File(resDir, "values/strings.xml")
        val urduFile = File(resDir, "values-ur/strings.xml")

        val defaultStrings = parseStringsXml(defaultFile)
        val urduStrings = parseStringsXml(urduFile)

        val unlocalizedKeys = mutableListOf<String>()

        defaultStrings.forEach { (key, defaultValue) ->
            val urduValue = urduStrings[key]
            assertNotNull("String $key should exist in values-ur/strings.xml", urduValue)

            if (key in allowedExceptionKeys) {
                assertEquals("String $key in Urdu should match default", defaultValue, urduValue)
            } else if (defaultValue == urduValue) {
                unlocalizedKeys.add(key)
            }
        }

        assertTrue(
            "Unlocalized keys in values-ur/strings.xml: $unlocalizedKeys",
            unlocalizedKeys.isEmpty(),
        )
    }

    @Test
    fun `urdu plurals for minutes and days contain one and other categories with placeholder`() {
        val resDir = findResDir()
        val urFile = File(resDir, "values-ur/strings.xml")
        val defaultFile = File(resDir, "values/strings.xml")

        val urMinutes = parsePluralItems(urFile, "minutes_plurals")
        val urDays = parsePluralItems(urFile, "days_plurals")

        val defaultMinutes = parsePluralItems(defaultFile, "minutes_plurals")
        val defaultDays = parsePluralItems(defaultFile, "days_plurals")

        assertTrue("Urdu minutes_plurals must have 'one'", urMinutes.containsKey("one"))
        assertTrue("Urdu minutes_plurals must have 'other'", urMinutes.containsKey("other"))
        assertNotEquals(defaultMinutes["one"], urMinutes["one"])
        assertNotEquals(defaultMinutes["other"], urMinutes["other"])

        assertTrue("Urdu days_plurals must have 'one'", urDays.containsKey("one"))
        assertTrue("Urdu days_plurals must have 'other'", urDays.containsKey("other"))
        assertNotEquals(defaultDays["one"], urDays["one"])
        assertNotEquals(defaultDays["other"], urDays["other"])

        // Format placeholder check (%1$d is 4 characters)
        assertEquals(1, countOccurrences(urMinutes["one"]!!))
        assertEquals(1, countOccurrences(urMinutes["other"]!!))
        assertEquals(1, countOccurrences(urDays["one"]!!))
        assertEquals(1, countOccurrences(urDays["other"]!!))
    }

    private fun countOccurrences(text: String): Int = text.windowed(4).count { it == "%1\$d" }

    private fun parseStringsXml(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val stringList = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()

        for (i in 0 until stringList.length) {
            val node = stringList.item(i)
            val isTranslatable = node.attributes?.getNamedItem("translatable")?.nodeValue != "false"
            val name = node.attributes?.getNamedItem("name")?.nodeValue
            val value = node.textContent?.trim()
            if (isTranslatable && name != null && value != null) {
                result[name] = value
            }
        }
        return result
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
                val items = node.childNodes
                for (j in 0 until items.length) {
                    val itemNode = items.item(j)
                    if (itemNode.nodeName == "item") {
                        val quantity = itemNode.attributes?.getNamedItem("quantity")?.nodeValue
                        val value = itemNode.textContent?.trim()
                        if (quantity != null && value != null) {
                            result[quantity] = value
                        }
                    }
                }
            }
        }
        return result
    }

    private fun findResDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "core/resources/src/main/res")
            if (candidate.exists() && candidate.isDirectory) return candidate
            val candidate2 = File(dir, "src/main/res")
            if (candidate2.exists() && candidate2.isDirectory && dir.name == "resources") return candidate2
            dir = dir.parentFile
        }
        error("Could not find core/resources/src/main/res directory")
    }
}
