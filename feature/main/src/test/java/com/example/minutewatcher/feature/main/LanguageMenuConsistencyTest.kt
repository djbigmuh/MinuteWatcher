package com.example.minutewatcher.feature.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LanguageMenuConsistencyTest {
    @Test
    fun `supportedLanguageOptions match locales configured in locales_config xml`() {
        val xmlFile = findLocalesConfigFile()
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

    private fun findLocalesConfigFile(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            val file = File(current, "core/resources/src/main/res/xml/locales_config.xml")
            if (file.exists()) return file
            current = current.parentFile
        }
        val dir = System.getProperty("user.dir")
        error("Could not locate core/resources/src/main/res/xml/locales_config.xml from $dir")
    }
}
