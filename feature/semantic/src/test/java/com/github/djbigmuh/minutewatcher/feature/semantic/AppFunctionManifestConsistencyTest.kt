package com.github.djbigmuh.minutewatcher.feature.semantic

import com.github.djbigmuh.minutewatcher.core.resources.testing.LocaleResourceDirs
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
        val manifestFile = findManifestFile()
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
            val foundValue =
                (0 until stringNodes.length)
                    .mapNotNull { stringNodes.item(it) }
                    .firstOrNull { node ->
                        node.attributes?.getNamedItem("name")?.nodeValue == "app_functions_display_description"
                    }?.textContent

            assertNotNull(foundValue)
            assertTrue(!foundValue.isNullOrBlank())
        }
    }

    @Test
    fun `declared service matches generated class name and assets`() {
        val manifestFile = findManifestFile()
        val doc = parseXml(manifestFile)
        val serviceNodes = doc.getElementsByTagName("service")
        val serviceClassName =
            (0 until serviceNodes.length)
                .mapNotNull { serviceNodes.item(it) as? Element }
                .firstOrNull { it.getAttribute("android:name").endsWith("MinuteWatcherAppFunctionService") }
                ?.getAttribute("android:name")

        assertNotNull("MinuteWatcherAppFunctionService must be declared", serviceClassName)
        assertEquals(
            "com.github.djbigmuh.minutewatcher.feature.semantic.appfunctions.MinuteWatcherAppFunctionService",
            serviceClassName,
        )

        val generatedClass =
            Class.forName(
                "com.github.djbigmuh.minutewatcher.feature.semantic.appfunctions.MinuteWatcherAppFunctionService",
            )
        assertNotNull(generatedClass)
    }

    private fun parseXml(file: File): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun verifyServiceDeclaration(doc: Document) {
        val serviceNodes = doc.getElementsByTagName("service")
        val service =
            (0 until serviceNodes.length)
                .mapNotNull { serviceNodes.item(it) as? Element }
                .firstOrNull { it.getAttribute("android:name").endsWith("MinuteWatcherAppFunctionService") }
        assertNotNull(service)
        if (service != null) {
            assertEquals("true", service.getAttribute("android:exported"))
            assertEquals(
                "android.permission.BIND_APP_FUNCTION_SERVICE",
                service.getAttribute("android:permission"),
            )
            verifyServiceChildren(service)
        }
    }

    private fun verifyServiceChildren(service: Element) {
        val children = service.childNodes
        val childElements =
            (0 until children.length)
                .mapNotNull { children.item(it) as? Element }

        val hasAction =
            childElements
                .filter { it.tagName == "intent-filter" }
                .any { hasAppFunctionAction(it) }

        val schemaProperty =
            childElements
                .filter { it.tagName == "property" }
                .any {
                    it.getAttribute("android:name") == "android.app.appfunctions.schema" &&
                        it.getAttribute("android:value").endsWith(".xsd")
                }

        val v2Property =
            childElements
                .filter { it.tagName == "property" }
                .any {
                    it.getAttribute("android:name") == "android.app.appfunctions.v2" &&
                        it.getAttribute("android:value").endsWith(".xml")
                }

        assertTrue(hasAction)
        assertTrue(schemaProperty)
        assertTrue(v2Property)
    }

    private fun hasAppFunctionAction(intentFilter: Element): Boolean {
        val actions = intentFilter.getElementsByTagName("action")
        return (0 until actions.length)
            .mapNotNull { actions.item(it) as? Element }
            .any { it.getAttribute("android:name") == "android.app.appfunctions.AppFunctionService" }
    }

    private fun verifyApplicationProperty(doc: Document) {
        val appNodes = doc.getElementsByTagName("application")
        assertTrue(appNodes.length > 0)
        val appNode = appNodes.item(0) as Element
        val props = appNode.getElementsByTagName("property")
        val metadataProperty =
            (0 until props.length)
                .mapNotNull { props.item(it) as? Element }
                .any {
                    it.getAttribute("android:name") == "android.app.appfunctions.app_metadata" &&
                        it.getAttribute("android:resource") == "@xml/app_metadata"
                }
        assertTrue(metadataProperty)
    }

    private fun findManifestFile(): File {
        val path =
            System.getProperty("minutewatcher.semanticManifestPath")
                ?: error("System property 'minutewatcher.semanticManifestPath' is not set")
        val file = File(path)
        assertTrue("Manifest file must exist at $path", file.exists())
        return file
    }

    private fun findFile(vararg paths: String): File {
        val rootPath =
            System.getProperty("minutewatcher.projectRoot")
                ?: error("System property 'minutewatcher.projectRoot' is not set")
        val dir = File(rootPath)
        for (path in paths) {
            val f = File(dir, path)
            if (f.exists()) return f
        }
        error("Could not find any of ${paths.joinToString()} from project root $rootPath")
    }

    private fun findResDir(): File {
        val rootPath =
            System.getProperty("minutewatcher.projectRoot")
                ?: error("System property 'minutewatcher.projectRoot' is not set")
        val candidate = File(rootPath, "core/resources/src/main/res")
        if (candidate.exists() && candidate.isDirectory) return candidate
        error("Could not locate core/resources/src/main/res at $candidate")
    }

    private fun parseLocalesConfig(file: File): Set<String> {
        val doc = parseXml(file)
        val localeNodes = doc.getElementsByTagName("locale")
        return (0 until localeNodes.length)
            .mapNotNull { localeNodes.item(it) }
            .mapNotNull { it.attributes?.getNamedItem("android:name")?.nodeValue }
            .filter { !it.isNullOrEmpty() }
            .toSet()
    }
}
