package host.serv.djbigmuh.minutewatcher.core.android.util

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class IconForgeDirectoryTest {
    private fun getProjectRootDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile!!
        }
        return dir
    }

    @Test
    fun `tools iconforge out directory does not exist in project tree`() {
        val rootDir = getProjectRootDir()
        val outDir = File(rootDir, "tools/iconforge/out")
        assertFalse("tools/iconforge/out directory must not exist", outDir.exists())
    }
}
