package host.serv.djbigmuh.minutewatcher.core.resources

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IconForgeReferenceSyncTest {
    private fun getProjectRootDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile!!
        }
        return dir
    }

    @Test
    fun `iconforge reference assets in out directory match canonical drawables byte for byte`() {
        val rootDir = getProjectRootDir()
        val outDir = File(rootDir, "tools/iconforge/out")
        assertTrue("tools/iconforge/out directory must exist", outDir.exists())

        val drawableDir = File(rootDir, "core/resources/src/main/res/drawable")
        assertTrue("core/resources/src/main/res/drawable directory must exist", drawableDir.exists())

        val referenceFiles = outDir.listFiles { _, name -> name.endsWith(".xml") }
        assertNotNull("tools/iconforge/out must contain reference xml files", referenceFiles)
        assertTrue("tools/iconforge/out must contain reference xml files", referenceFiles!!.isNotEmpty())

        referenceFiles.forEach { refFile ->
            val canonicalFile = File(drawableDir, refFile.name)
            assertTrue(
                "Canonical file ${refFile.name} must exist in core/resources/src/main/res/drawable/",
                canonicalFile.exists(),
            )

            val refBytes = refFile.readBytes()
            val canonicalBytes = canonicalFile.readBytes()

            assertArrayEquals(
                "Reference file ${refFile.name} in tools/iconforge/out/ must be byte-for-byte identical to canonical drawable",
                canonicalBytes,
                refBytes,
            )
        }
    }
}
