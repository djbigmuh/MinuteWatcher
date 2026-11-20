package com.github.djbigmuh.minutewatcher.core.resources

import com.github.djbigmuh.minutewatcher.core.resources.testing.LocaleResourceDirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
                    LocaleResourceDirs.languageTagOfQualifier(dir.name)
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
            val dirName = LocaleResourceDirs.resourceDirForTag(lang)
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
            val dirName = LocaleResourceDirs.resourceDirForTag(lang)
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

    @Test
    fun `minutes widget provider metadata is valid`() {
        val resDir = findResDir()
        val widgetInfoFile = File(resDir, "xml/minutes_widget_info.xml")
        assertTrue("minutes_widget_info.xml must exist", widgetInfoFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(widgetInfoFile)
        val root = doc.documentElement
        assertEquals("appwidget-provider", root.tagName)

        assertEquals("@string/widget_description", root.getAttribute("android:description"))
        assertEquals("@layout/widget_picker_preview", root.getAttribute("android:previewLayout"))
        assertEquals("@drawable/widget_preview", root.getAttribute("android:previewImage"))
        assertEquals("@layout/widget_layout", root.getAttribute("android:initialLayout"))
        assertEquals("3", root.getAttribute("android:targetCellWidth"))
        assertEquals("2", root.getAttribute("android:targetCellHeight"))
        assertEquals("0", root.getAttribute("android:updatePeriodMillis"))
    }

    @Test
    fun `widget_description string is present and under 100 chars for all locales`() {
        val resDir = findResDir()
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        val configuredLocales = parseLocalesConfig(localesConfigFile)

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        configuredLocales.forEach { lang ->
            val dirName = LocaleResourceDirs.resourceDirForTag(lang)
            val stringsFile = File(resDir, "$dirName/strings.xml")
            assertTrue("strings.xml for locale '$lang' must exist in $dirName", stringsFile.exists())

            val doc = builder.parse(stringsFile)
            val stringNodes = doc.getElementsByTagName("string")
            var foundValue: String? = null

            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i)
                val nameAttr = node.attributes?.getNamedItem("name")?.nodeValue
                if (nameAttr == "widget_description") {
                    foundValue = node.textContent
                    break
                }
            }

            assertTrue(
                "Key 'widget_description' must exist in $dirName/strings.xml",
                foundValue != null,
            )
            assertTrue(
                "Key 'widget_description' must not be empty in $dirName/strings.xml",
                !foundValue.isNullOrBlank(),
            )
            assertTrue(
                "Key 'widget_description' length must be under 100 chars in $dirName/strings.xml " +
                    "(actual: ${foundValue!!.length})",
                foundValue.length < 100,
            )
        }
    }

    @Test
    fun `indonesian locale resolves to values-in and absence of values-id`() {
        val resDir = findResDir()
        assertEquals("values-in", LocaleResourceDirs.resourceDirForTag("id"))
        assertEquals("id", LocaleResourceDirs.languageTagOfQualifier("values-in"))
        assertFalse(
            "values-id directory must not exist",
            File(resDir, "values-id").exists(),
        )
        assertTrue(
            "values-in directory must exist",
            File(resDir, "values-in").exists(),
        )
    }

    @Test
    fun `urdu locale strings exist, do not overlap with arabic and maintain digit consistency`() {
        val resDir = findResDir()
        val urFile = File(resDir, "values-ur/strings.xml")
        val arFile = File(resDir, "values-ar/strings.xml")
        assertTrue("values-ur/strings.xml must exist", urFile.exists())
        assertTrue("values-ar/strings.xml must exist", arFile.exists())

        val urText = urFile.readText()
        val arText = arFile.readText()
        assertFalse("Urdu strings must not be identical to Arabic strings", urText == arText)
        assertFalse("Urdu strings must not contain Arabic-specific strings", urText.contains("بطاقات SIM"))
        assertTrue("Urdu strings must contain correct parameter formatting", urText.contains("%1\$d"))
    }

    @Test
    fun `about author email is correctly set in donottranslate xml`() {
        val resDir = findResDir()
        val donottranslateFile = File(resDir, "values/donottranslate.xml")
        assertTrue("donottranslate.xml must exist", donottranslateFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(donottranslateFile)
        val stringNodes = doc.getElementsByTagName("string")
        var emailValue: String? = null

        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val nameAttr = node.attributes?.getNamedItem("name")?.nodeValue
            if (nameAttr == "about_author_email") {
                emailValue = node.textContent
                break
            }
        }

        assertEquals("dj.bigmuh@gmail.com", emailValue)
        assertTrue(emailValue?.contains("@") == true && emailValue.contains(".") == true)
    }

    @Test
    fun `about string keys are present and non empty across all locale resource directories`() {
        val resDir = findResDir()
        val valueDirs =
            resDir
                .listFiles { file -> file.isDirectory && file.name.startsWith("values") }
                ?.filter { dir -> File(dir, "strings.xml").exists() } ?: emptyList()

        assertTrue("Must have multiple locale directories", valueDirs.isNotEmpty())

        val requiredKeys =
            setOf(
                "about_title",
                "about_message",
                "about_author_name",
                "about_email_label",
                "about_signoff",
                "about_version_format",
                "about_content_desc",
            )

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        valueDirs.forEach { dir ->
            val stringsFile = File(dir, "strings.xml")
            val doc = builder.parse(stringsFile)
            val stringNodes = doc.getElementsByTagName("string")
            val foundKeys = mutableSetOf<String>()

            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i)
                val nameAttr = node.attributes?.getNamedItem("name")?.nodeValue
                if (nameAttr != null) {
                    foundKeys.add(nameAttr)
                }
            }

            requiredKeys.forEach { key ->
                assertTrue(
                    "Key '$key' must exist in ${dir.name}/strings.xml",
                    foundKeys.contains(key),
                )
            }
        }
    }

    @Test
    fun `about_author_name contains Pavel Mukhin or Russian equivalent across all 28 locales`() {
        val resDir = findResDir()
        val valueDirs =
            resDir
                .listFiles { file -> file.isDirectory && file.name.startsWith("values") }
                ?.filter { dir -> File(dir, "strings.xml").exists() } ?: emptyList()

        assertEquals("Must have exactly 28 locale resource directories", 28, valueDirs.size)

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        valueDirs.forEach { dir ->
            val stringsFile = File(dir, "strings.xml")
            val doc = builder.parse(stringsFile)
            val stringNodes = doc.getElementsByTagName("string")
            var authorName: String? = null

            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i)
                val nameAttr = node.attributes?.getNamedItem("name")?.nodeValue
                if (nameAttr == "about_author_name") {
                    authorName = node.textContent
                    break
                }
            }

            assertNotNull("about_author_name must exist in ${dir.name}/strings.xml", authorName)
            if (dir.name == "values-ru") {
                assertTrue(
                    "about_author_name in values-ru must contain 'Павел Мухин'",
                    authorName!!.contains("Павел Мухин"),
                )
            } else {
                assertTrue(
                    "about_author_name in ${dir.name} must contain 'Pavel Mukhin'",
                    authorName!!.contains("Pavel Mukhin"),
                )
            }
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
