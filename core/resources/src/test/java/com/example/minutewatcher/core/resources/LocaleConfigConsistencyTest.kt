package com.example.minutewatcher.core.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocaleConfigConsistencyTest {
    @Test
    fun `locales_config matches res values directories containing strings xml`() {
        val resDir = findResDir()
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        assertTrue("locales_config.xml must exist", localesConfigFile.exists())

        val configuredLocales = parseLocalesConfig(localesConfigFile)
        assertTrue("locales_config.xml must contain 'en'", configuredLocales.contains("en"))

        val resourceLocales =
            resDir
                .listFiles { file -> file.isDirectory && file.name.startsWith("values") }
                ?.filter { dir -> File(dir, "strings.xml").exists() }
                ?.map { dir ->
                    if (dir.name == "values") "en" else dir.name.removePrefix("values-")
                }?.toSet() ?: emptySet()

        assertEquals(
            "Locales in locales_config.xml must match directories in res with strings.xml",
            resourceLocales,
            configuredLocales,
        )
    }

    @Test
    fun `strings xml files do not contain MissingTranslation suppression`() {
        val resDir = findResDir()
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        val configuredLocales = parseLocalesConfig(localesConfigFile)

        configuredLocales.forEach { lang ->
            val dirName = if (lang == "en") "values" else "values-$lang"
            val stringsFile = File(resDir, "$dirName/strings.xml")
            assertTrue("strings.xml for locale '$lang' must exist in $dirName", stringsFile.exists())

            val content = stringsFile.readText()
            assertFalse(
                "strings.xml in $dirName must not contain tools:ignore=\"MissingTranslation\"",
                content.contains("MissingTranslation"),
            )
        }
    }

    @Test
    fun `ic_language and ic_palette drawable resources exist and are valid vector drawables`() {
        val resDir = findResDir()
        val icLanguage = File(resDir, "drawable/ic_language.xml")
        val icPalette = File(resDir, "drawable/ic_palette.xml")

        assertTrue("ic_language.xml drawable resource must exist", icLanguage.exists())
        assertTrue("ic_palette.xml drawable resource must exist", icPalette.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        val docLang = builder.parse(icLanguage)
        assertEquals("vector", docLang.documentElement.tagName)

        val docPal = builder.parse(icPalette)
        assertEquals("vector", docPal.documentElement.tagName)
    }

    @Test
    fun `banner_dismiss_content_desc string key is present and non empty for all configured locales`() {
        val resDir = findResDir()
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        val configuredLocales = parseLocalesConfig(localesConfigFile)

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        configuredLocales.forEach { lang ->
            val dirName = if (lang == "en") "values" else "values-$lang"
            val stringsFile = File(resDir, "$dirName/strings.xml")
            assertTrue("strings.xml for locale '$lang' must exist in $dirName", stringsFile.exists())

            val doc = builder.parse(stringsFile)
            val stringNodes = doc.getElementsByTagName("string")
            var foundValue: String? = null

            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i)
                val nameAttr = node.attributes?.getNamedItem("name")?.nodeValue
                if (nameAttr == "banner_dismiss_content_desc") {
                    foundValue = node.textContent
                    break
                }
            }

            assertTrue(
                "Key 'banner_dismiss_content_desc' must exist in $dirName/strings.xml",
                foundValue != null,
            )
            assertTrue(
                "Key 'banner_dismiss_content_desc' must not be empty in $dirName/strings.xml",
                !foundValue.isNullOrBlank(),
            )
        }
    }

    private fun parseLocalesConfig(file: File): Set<String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val localeNodes = doc.getElementsByTagName("locale")
        val result = mutableSetOf<String>()

        for (i in 0 until localeNodes.length) {
            val node = localeNodes.item(i)
            val nameAttr = node.attributes?.getNamedItem("android:name")?.nodeValue
            if (!nameAttr.isNullOrEmpty()) {
                result.add(nameAttr)
            }
        }
        return result
    }

    private fun findResDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidate1 = File(userDir, "src/main/res")
        if (candidate1.exists() && candidate1.isDirectory) return candidate1
        val candidate2 = File(userDir, "core/resources/src/main/res")
        if (candidate2.exists() && candidate2.isDirectory) return candidate2
        error("Could not locate core/resources/src/main/res from ${userDir.absolutePath}")
    }
}
