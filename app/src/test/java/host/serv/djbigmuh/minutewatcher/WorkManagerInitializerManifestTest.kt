package host.serv.djbigmuh.minutewatcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class WorkManagerInitializerManifestTest {
    private fun getProjectRootDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile!!
        }
        return dir
    }

    @Test
    fun `manifest contains WorkManagerInitializer remove rule`() {
        val rootDir = getProjectRootDir()
        val manifestFile = File(rootDir, "app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(manifestFile)
        val metaDataNodes = doc.getElementsByTagName("meta-data")
        var foundRemoveRule = false

        for (i in 0 until metaDataNodes.length) {
            val node = metaDataNodes.item(i) as org.w3c.dom.Element
            val name = node.getAttribute("android:name")
            val nodeAttr = node.getAttribute("tools:node")
            if (name == "androidx.work.WorkManagerInitializer" && nodeAttr == "remove") {
                foundRemoveRule = true
                break
            }
        }
        assertTrue(
            "WorkManagerInitializer with tools:node=\"remove\" must be present in AndroidManifest.xml",
            foundRemoveRule,
        )
    }

    @Test
    fun `MinuteWatcherApp implements Configuration Provider`() {
        val app = ApplicationProvider.getApplicationContext<Context>() as? Configuration.Provider
        assertNotNull("MinuteWatcherApp must implement Configuration.Provider", app)
        assertNotNull("workManagerConfiguration must not be null", app?.workManagerConfiguration)
    }
}
