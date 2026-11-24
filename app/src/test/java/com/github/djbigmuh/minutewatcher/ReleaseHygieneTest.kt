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
        val forbiddenNames =
            listOf(
                "BenchmarkPopulateWorker",
                "BenchmarkUseCaseImpl",
            )

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
                    "Forbidden benchmark/mock class '$forbidden' found in production code: ${file.absolutePath}",
                    content.contains(forbidden),
                )
            }
        }
    }

    @Test
    fun `verify release manifest does not contain benchmark receiver or exported debug components`() {
        val manifestFile = ProjectPaths.appManifest
        assertTrue("Main manifest must exist", manifestFile.exists())

        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(manifestFile)
        doc.documentElement.normalize()

        val receivers = doc.getElementsByTagName("receiver")
        for (i in 0 until receivers.length) {
            val receiver = receivers.item(i)
            val name = receiver.attributes?.getNamedItem("android:name")?.nodeValue ?: continue
            assertFalse(
                "BenchmarkReceiver must not be in main manifest: $name",
                name.contains("BenchmarkReceiver"),
            )
        }
    }
}
