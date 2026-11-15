package host.serv.djbigmuh.minutewatcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenseConsistencyTest {
    private fun findProjectRoot(): File {
        val rootPath =
            System.getProperty("minutewatcher.projectRoot")
                ?: error("System property 'minutewatcher.projectRoot' is not set")
        val dir = File(rootPath)
        if (!dir.exists()) {
            error("Project root does not exist at $rootPath")
        }
        return dir
    }

    @Test
    fun `license file exists in project root and contains valid mit text and copyright`() {
        val root = findProjectRoot()
        val licenseFile = File(root, "LICENSE")
        assertTrue("LICENSE file must exist in project root", licenseFile.exists())

        val content = licenseFile.readText()
        assertTrue(
            "LICENSE must contain MIT permission text",
            content.contains("Permission is hereby granted, free of charge"),
        )
        assertTrue(
            "LICENSE must contain copyright for Pavel Mukhin",
            content.contains("Copyright (c) 2026 Pavel Mukhin"),
        )
    }

    @Test
    fun `readme references license file`() {
        val root = findProjectRoot()
        val readmeFile = File(root, "README.md")
        assertTrue("README.md must exist in project root", readmeFile.exists())

        val content = readmeFile.readText()
        assertTrue(
            "README.md must contain link to [LICENSE](LICENSE)",
            content.contains("[LICENSE](LICENSE)"),
        )
    }
}
