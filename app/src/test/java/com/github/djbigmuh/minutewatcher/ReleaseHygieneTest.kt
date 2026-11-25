package com.github.djbigmuh.minutewatcher

import com.github.djbigmuh.minutewatcher.core.resources.testing.ProjectPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReleaseHygieneTest {
    private val projectRoot: File
        get() = ProjectPaths.root

    @Test
    fun `verify no benchmark population code exists in main source sets`() {
        val forbiddenDirs = listOf("src/mockBenchmark", "src/benchmark", "src/testBenchmark", "src/testFixtures")

        val forbiddenNames = mutableSetOf<String>()
        projectRoot.walkTopDown().forEach { file ->
            if (file.isFile) {
                val path = file.absolutePath.replace('\\', '/')
                if (forbiddenDirs.any { path.contains("/$it/") } &&
                    (file.extension == "kt" || file.extension == "java")
                ) {
                    forbiddenNames.add(file.nameWithoutExtension)
                }
            }
        }

        val prodFiles =
            projectRoot
                .walkTopDown()
                .filter { file ->
                    if (!file.isFile) return@filter false
                    val path = file.absolutePath.replace('\\', '/')
                    path.contains("/src/main/") &&
                        !path.contains("/build/") &&
                        !path.contains("/macrobenchmark/") &&
                        !path.contains("/baselineprofile/")
                }.toList()

        for (file in prodFiles) {
            val content = file.readText()
            for (forbidden in forbiddenNames) {
                assertFalse(
                    "Forbidden benchmark/mock class '$forbidden' found in production file ${file.absolutePath}",
                    content.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun `verify release merged manifest does not contain benchmark receiver or benchmark components`() {
        val manifestPath = System.getProperty("minutewatcher.releaseMergedManifestPath")
        val manifestFile =
            if (manifestPath != null) {
                File(manifestPath)
            } else {
                File(
                    projectRoot,
                    "app/build/intermediates/merged_manifest/release/processReleaseManifest/AndroidManifest.xml",
                )
            }
        assertTrue("Release merged manifest must exist at $manifestFile", manifestFile.exists())

        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(manifestFile)
        doc.documentElement.normalize()

        listOf("receiver", "service", "activity", "provider").forEach { tag ->
            val elements = doc.getElementsByTagName(tag)
            for (i in 0 until elements.length) {
                val el = elements.item(i)
                val name = el.attributes?.getNamedItem("android:name")?.nodeValue ?: continue
                assertFalse(
                    "Benchmark component '$name' must not be in release merged manifest (${manifestFile.absolutePath})",
                    name.contains("Benchmark") || name.contains("benchmark"),
                )
            }
        }
    }
}
