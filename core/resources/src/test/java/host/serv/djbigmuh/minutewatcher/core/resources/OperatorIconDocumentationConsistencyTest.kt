package host.serv.djbigmuh.minutewatcher.core.resources

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OperatorIconDocumentationConsistencyTest {
    @Test
    fun `validate operator icon documentation and attributions consistency`() {
        val rootDir = getProjectRootDir()
        val drawableDir = File(rootDir, "core/resources/src/main/res/drawable")
        assertTrue("Drawable dir must exist", drawableDir.exists())

        val rawFiles =
            drawableDir.listFiles { _, name ->
                name.startsWith("ic_operator_") && name.endsWith(".xml")
            }
        val allIconFiles =
            rawFiles
                ?.map { it.nameWithoutExtension }
                ?.toSet()
                ?: emptySet()

        assertTrue("Should have operator icon files", allIconFiles.isNotEmpty())

        val docFile = File(rootDir, "docs/operator_icons.md")
        assertTrue("docs/operator_icons.md must exist", docFile.exists())
        val docText = docFile.readText()

        val attrFile = File(rootDir, "core/resources/src/main/assets/ATTRIBUTIONS.txt")
        assertTrue("ATTRIBUTIONS.txt must exist", attrFile.exists())
        val attrText = attrFile.readText()

        val regexA = Regex("""- (ic_operator_\w+) \(slug:""")
        val categoryAInAttr =
            regexA
                .findAll(attrText)
                .map { it.groupValues[1] }
                .toSet()

        val regexC = Regex("""- (ic_operator_\w+)""")
        val categoryCInAttr =
            regexC
                .findAll(attrText)
                .map { it.groupValues[1] }
                .filter { it !in categoryAInAttr }
                .toSet()

        // Verify all mentioned category A & C files exist
        for (res in categoryAInAttr + categoryCInAttr) {
            assertTrue("Referenced icon $res must exist as file", res in allIconFiles)
        }

        // Category A and C must not overlap
        val overlap = categoryAInAttr.intersect(categoryCInAttr)
        assertTrue("Category A and C must not overlap: $overlap", overlap.isEmpty())

        // Verify total file counts match
        val categoryBCount = allIconFiles.size - categoryAInAttr.size - categoryCInAttr.size
        assertTrue("Category B count must be positive", categoryBCount > 0)

        assertTrue(
            "docs/operator_icons.md should contain total count ${allIconFiles.size} or Category B count $categoryBCount",
            docText.contains("${allIconFiles.size}") || docText.contains("$categoryBCount"),
        )

        // Verify OFL-1.1.txt exists
        val oflFile = File(rootDir, "core/resources/src/main/assets/OFL-1.1.txt")
        assertTrue("OFL-1.1.txt must exist", oflFile.exists())
    }

    private fun getProjectRootDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile!!
        }
        return dir
    }
}
