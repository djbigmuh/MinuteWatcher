package com.example.minutewatcher.feature.semantic

import com.example.minutewatcher.core.resources.testing.LocaleResourceDirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppFunctionManifestConsistencyTest {
    @Test
    fun `manifest declares app function service with required security and properties`() {
        val manifestFile =
            findFile("feature/semantic/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val doc = parseXml(manifestFile)
        verifyServiceDeclaration(doc)
        verifyApplicationProperty(doc)
    }

    @Test
    fun `app metadata xml is valid and contains required attributes`() {
        val metadataFile =
            findFile("feature/semantic/src/main/res/xml/app_metadata.xml", "src/main/res/xml/app_metadata.xml")
        assertTrue("app_metadata.xml must exist", metadataFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val doc = factory.newDocumentBuilder().parse(metadataFile)

        val root = doc.documentElement
        assertEquals("AppFunctionAppMetadata", root.tagName)

        val description =
            root.getAttributeNS("http://schemas.android.com/apk/androidx.appfunctions", "description")
        assertTrue("appfn:description must not be empty", description.isNotBlank())

        val displayDescription =
            root.getAttributeNS("http://schemas.android.com/apk/androidx.appfunctions", "displayDescription")
        assertEquals("@string/app_functions_display_description", displayDescription)
    }

    @Test
    fun `app_functions_display_description string exists and is non empty for all locales`() {
        val resDir = findResDir()
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        assertTrue("locales_config.xml must exist", localesConfigFile.exists())

        val configuredLocales = parseLocalesConfig(localesConfigFile)
        assertTrue("Configured locales must contain en", configuredLocales.contains("en"))

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()

        configuredLocales.forEach { lang ->
            val dirName = LocaleResourceDirs.resourceDirForTag(lang)
            val stringsFile = File(resDir, "$dirName/strings.xml")
            assertTrue("Strings file must exist for $lang ($dirName/strings.xml)", stringsFile.exists())

            val doc = builder.parse(stringsFile)
            val stringNodes = doc.getElementsByTagName("string")
            var foundValue: String? = null

            for (i in 0 until stringNodes.length) {
                val node = stringNodes.item(i)
                val nameAttr = node.attributes?.getNamedItem("name")?.nodeValue
                if (nameAttr == "app_functions_display_description") {
                    foundValue = node.textContent
                    break
                }
            }

            assertNotNull(foundValue)
            assertTrue(!foundValue.isNullOrBlank())
        }
    }

    private fun parseXml(file: File): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun verifyServiceDeclaration(doc: Document) {
        val serviceNodes = doc.getElementsByTagName("service")
        var foundService = false
        for (i in 0 until serviceNodes.length) {
            val service = serviceNodes.item(i) as? Element ?: continue
            val name = service.getAttribute("android:name")
            if (name.endsWith("MinuteWatcherAppFunctionService")) {
                foundService = true
                assertEquals("true", service.getAttribute("android:exported"))
                assertEquals(
                    "android.permission.BIND_APP_FUNCTION_SERVICE",
                    service.getAttribute("android:permission"),
                )
                verifyServiceChildren(service)
            }
        }
        assertTrue(foundService)
    }

    private fun verifyServiceChildren(service: Element) {
        val children = service.childNodes
        var hasAction = false
        var schemaProperty = false
        var v2Property = false

        for (j in 0 until children.length) {
            val child = children.item(j) as? Element ?: continue
            if (child.tagName == "intent-filter") {
                hasAction = hasAppFunctionAction(child)
            } else if (child.tagName == "property") {
                val propName = child.getAttribute("android:name")
                val propVal = child.getAttribute("android:value")
                if (propName == "android.app.appfunctions.schema" && propVal.endsWith(".xsd")) {
                    schemaProperty = true
                }
                if (propName == "android.app.appfunctions.v2" && propVal.endsWith(".xml")) {
                    v2Property = true
                }
            }
        }
        assertTrue(hasAction)
        assertTrue(schemaProperty)
        assertTrue(v2Property)
    }

    private fun hasAppFunctionAction(intentFilter: Element): Boolean {
        val actions = intentFilter.getElementsByTagName("action")
        for (k in 0 until actions.length) {
            val action = actions.item(k) as? Element ?: continue
            if (action.getAttribute("android:name") == "android.app.appfunctions.AppFunctionService") {
                return true
            }
        }
        return false
    }

    private fun verifyApplicationProperty(doc: Document) {
        val appNodes = doc.getElementsByTagName("application")
        assertTrue(appNodes.length > 0)
        val appNode = appNodes.item(0) as Element
        val props = appNode.getElementsByTagName("property")
        var metadataProperty = false
        for (i in 0 until props.length) {
            val prop = props.item(i) as Element
            if (prop.getAttribute("android:name") == "android.app.appfunctions.app_metadata" &&
                prop.getAttribute("android:resource") == "@xml/app_metadata"
            ) {
                metadataProperty = true
            }
        }
        assertTrue(metadataProperty)
    }

    private fun findFile(vararg paths: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (path in paths) {
                val f = File(dir, path)
                if (f.exists()) return f
            }
            dir = dir.parentFile
        }
        error("Could not find any of ${paths.joinToString()}")
    }

    private fun findResDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "core/resources/src/main/res")
            if (candidate.exists() && candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("Could not locate core/resources/src/main/res")
    }

    private fun parseLocalesConfig(file: File): Set<String> {
        val doc = parseXml(file)
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
}
