package com.github.djbigmuh.minutewatcher

import com.github.djbigmuh.minutewatcher.core.resources.testing.ProjectPaths
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenseConsistencyTest {
    private fun findProjectRoot(): File = ProjectPaths.root

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
