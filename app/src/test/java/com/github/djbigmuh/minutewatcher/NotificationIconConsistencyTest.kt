package com.github.djbigmuh.minutewatcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.resources.testing.ProjectPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
class NotificationIconConsistencyTest {
    private fun findProjectRoot(): File = ProjectPaths.root

    private fun findAppManifestFile(): File = ProjectPaths.appManifest

    @Test
    fun `usage tile service in manifest uses ic_stat_minutewatcher`() {
        val manifestFile = findAppManifestFile()
        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(manifestFile)
        val services = doc.getElementsByTagName("service")
        var foundTileService = false
        for (i in 0 until services.length) {
            val service = services.item(i) as org.w3c.dom.Element
            val name = service.getAttribute("android:name")
            if (name.contains("UsageTileService")) {
                foundTileService = true
                val icon = service.getAttribute("android:icon")
                assertEquals("@drawable/ic_stat_minutewatcher", icon)
            }
        }
        assertTrue("UsageTileService must be declared in manifest", foundTileService)
    }

    @Test
    fun `ic_stat_minutewatcher vector drawable meets all specifications`() {
        val root = findProjectRoot()
        val iconFile = File(root, "core/resources/src/main/res/drawable/ic_stat_minutewatcher.xml")
        assertTrue("ic_stat_minutewatcher.xml must exist", iconFile.exists())

        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(iconFile)
        val vector = doc.documentElement
        assertEquals("vector", vector.nodeName)

        val viewportWidth = vector.getAttribute("android:viewportWidth")
        val viewportHeight = vector.getAttribute("android:viewportHeight")
        assertEquals("24", viewportWidth)
        assertEquals("24", viewportHeight)

        val groups = vector.getElementsByTagName("group")
        assertEquals("No group elements allowed", 0, groups.length)

        val paths = vector.getElementsByTagName("path")
        assertTrue("Must have path elements", paths.length > 0)
        for (i in 0 until paths.length) {
            val path = paths.item(i) as org.w3c.dom.Element
            val fillColor = path.getAttribute("android:fillColor")
            assertEquals("#FFFFFFFF", fillColor)
        }
    }

    @Test
    fun `ic_notification resource is absent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resId = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
        assertEquals("ic_notification resource must not exist", 0, resId)

        val root = findProjectRoot()
        val oldFile = File(root, "core/resources/src/main/res/drawable/ic_notification.xml")
        assertFalse("ic_notification.xml file must not exist", oldFile.exists())
    }
}
