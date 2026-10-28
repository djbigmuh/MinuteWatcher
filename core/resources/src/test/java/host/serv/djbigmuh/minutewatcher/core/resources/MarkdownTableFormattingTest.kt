package host.serv.djbigmuh.minutewatcher.core.resources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownTableFormattingTest {
    private fun getProjectRootDir(): File {
        val prop = System.getProperty("minutewatcher.coreResourcesResDir")
        var dir: File? = if (prop != null) File(prop) else File(System.getProperty("user.dir") ?: ".")
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir ?: File(".")
    }

    @Test
    fun `all tracked markdown files have valid table formatting`() {
        val rootDir = getProjectRootDir()
        val mdFiles =
            rootDir
                .walkTopDown()
                .filter {
                    it.extension.lowercase() == "md" &&
                        !it.absolutePath.contains(".git") &&
                        !it.absolutePath.contains("build")
                }.toList()

        assertTrue("Markdown files must exist", mdFiles.isNotEmpty())

        val failures = mutableListOf<String>()
        for (file in mdFiles) {
            checkFileHygiene(file, failures)
        }

        assertTrue("Markdown table failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun checkFileHygiene(
        file: File,
        failures: MutableList<String>,
    ) {
        val lines = file.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return

        val rawText = file.readText(Charsets.UTF_8)
        assertTrue("File ${file.name} must end with a newline", rawText.endsWith("\n"))
        assertFalse("File ${file.name} must not end with multiple newlines", rawText.endsWith("\n\n"))

        for ((idx, line) in lines.withIndex()) {
            if (line.endsWith(" ") || line.endsWith("\t")) {
                failures.add("${file.name}:${idx + 1} ends with whitespace")
            }
        }

        checkTables(lines, file.name, failures)
    }

    private fun checkTables(
        lines: List<String>,
        fileName: String,
        failures: MutableList<String>,
    ) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                val tableLines = mutableListOf<String>()
                while (
                    i < lines.size &&
                    lines[i].trim().startsWith("|") &&
                    lines[i].trim().endsWith("|")
                ) {
                    tableLines.add(lines[i])
                    i++
                }
                validateTableBlock(tableLines, fileName, failures)
            } else {
                i++
            }
        }
    }

    private fun validateTableBlock(
        tableLines: List<String>,
        fileName: String,
        failures: MutableList<String>,
    ) {
        if (tableLines.size < 2) return
        val sepLine = tableLines[1].trim()
        val sepCells = sepLine.split("|").filter { it.isNotEmpty() }
        val isSeparator =
            sepCells.all { cell ->
                cell.trim().matches(Regex("^:?-+:?$"))
            }
        if (!isSeparator) return

        val headerCellsCount = tableLines[0].split("|").size
        for ((tIdx, tLine) in tableLines.withIndex()) {
            val cellsCount = tLine.split("|").size
            if (cellsCount != headerCellsCount) {
                failures.add(
                    "$fileName table row $tIdx cell count ($cellsCount) " +
                        "does not match header ($headerCellsCount)",
                )
            }
        }
    }
}
