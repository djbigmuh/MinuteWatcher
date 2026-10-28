package host.serv.djbigmuh.minutewatcher.feature.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LanguageMenuConsistencyTest {
    @Test
    fun `supportedLanguageOptions match locales configured in locales_config xml`() {
        val xmlFile = findLocalesConfigFile("core/resources/src/main/res/xml/locales_config.xml")
        assertTrue("locales_config.xml must exist at ${xmlFile.absolutePath}", xmlFile.exists())

        val xmlLocales = parseLocalesConfig(xmlFile)
        val menuLocales = supportedLanguageOptions.mapNotNull { it.code }.toSet()

        assertEquals(
            "Language options in supportedLanguageOptions must match locales_config.xml.\n" +
                "Menu codes: $menuLocales\n" +
                "XML codes: $xmlLocales",
            xmlLocales,
            menuLocales,
        )
    }

    @Test
    fun `no emojis or forbidden code points in MainScreenMenus and SimCardUiModels`() {
        val files =
            listOf(
                findLocalesConfigFile(
                    "feature/main/src/main/java/host/serv/djbigmuh/minutewatcher/feature/main/SimCardUiModels.kt",
                ),
                findLocalesConfigFile(
                    "feature/main/src/main/java/host/serv/djbigmuh/minutewatcher/" +
                        "feature/main/ui/components/MainScreenMenus.kt",
                ),
            )
        for (file in files) {
            val content = file.readText()
            for (char in content) {
                val codePoint = char.code
                assertFalse(
                    "Forbidden emoji code point found: $codePoint",
                    codePoint in 0x1F1E6..0x1F1FF ||
                        codePoint in 0x1F300..0x1F5FF ||
                        codePoint in 0x2600..0x27BF,
                )
            }
        }
    }

    @Test
    fun `non system language strings have translatable false in strings xml`() {
        val stringsFile = findLocalesConfigFile("core/resources/src/main/res/values/strings.xml")
        assertTrue(stringsFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(stringsFile)
        val stringNodes = doc.getElementsByTagName("string")
        val nonSystemLangs =
            listOf(
                "language_en",
                "language_ru",
                "language_de",
                "language_fr",
                "language_es",
                "language_ar",
                "language_ur",
                "language_hi",
                "language_bn",
                "language_id",
                "language_ja",
                "language_zh",
                "language_pt_br",
                "language_pt_pt",
                "language_ta",
                "language_te",
                "language_mr",
                "language_gu",
                "language_pa",
                "language_vi",
                "language_tr",
                "language_ko",
                "language_it",
                "language_fa",
                "language_ha",
                "language_yo",
                "language_jv",
                "language_bho",
            )

        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val name = node.attributes?.getNamedItem("name")?.nodeValue
            if (name in nonSystemLangs) {
                val translatable = node.attributes?.getNamedItem("translatable")?.nodeValue
                assertEquals("false", translatable)
            }
        }
    }

    @Test
    fun `theme vector resources exist and have 24dp dimensions`() {
        val themeDrawables = listOf("ic_theme_system.xml", "ic_theme_light.xml", "ic_theme_dark.xml")
        for (drawableName in themeDrawables) {
            val file = findLocalesConfigFile("core/resources/src/main/res/drawable/$drawableName")
            assertTrue("Drawable $drawableName must exist", file.exists())

            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val root = doc.documentElement
            assertEquals("vector", root.tagName)
            val width = root.attributes?.getNamedItem("android:width")?.nodeValue
            val height = root.attributes?.getNamedItem("android:height")?.nodeValue
            assertEquals("24dp", width)
            assertEquals("24dp", height)
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

    private fun findLocalesConfigFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            val file = File(current, relativePath)
            if (file.exists()) return file
            current = current.parentFile
        }
        val dir = System.getProperty("user.dir")
        error("Could not locate $relativePath from $dir")
    }
}
