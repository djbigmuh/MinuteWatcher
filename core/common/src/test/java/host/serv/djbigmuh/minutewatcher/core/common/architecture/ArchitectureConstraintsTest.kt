package host.serv.djbigmuh.minutewatcher.core.common.architecture

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ArchitectureConstraintsTest {

    private val projectRoot: File by lazy {
        val rootPath = System.getProperty("minutewatcher.projectRoot")
            ?: error("System property minutewatcher.projectRoot is not set")
        File(rootPath)
    }

    private fun findProductionKtFiles(): List<File> {
        val files = mutableListOf<File>()
        projectRoot.walkTopDown().forEach { file ->
            if (file.isFile && file.extension == "kt") {
                val normalizedPath = file.absolutePath.replace('\\', '/')
                if (normalizedPath.contains("/src/main/") && !normalizedPath.contains("/build/")) {
                    files.add(file)
                }
            }
        }
        return files
    }

    private fun findAllKtFiles(): List<File> {
        val files = mutableListOf<File>()
        projectRoot.walkTopDown().forEach { file ->
            if (file.isFile && (file.extension == "kt" || file.extension == "kts")) {
                val normalizedPath = file.absolutePath.replace('\\', '/')
                if (!normalizedPath.contains("/build/") && !normalizedPath.contains("/.gradle/")) {
                    files.add(file)
                }
            }
        }
        return files
    }

    @Test
    fun `production code does not use direct Dispatchers IO, Default, or Main`() {
        val prodFiles = findProductionKtFiles().filter { file ->
            val path = file.absolutePath.replace('\\', '/')
            !path.endsWith("/DispatchersModule.kt") && !path.endsWith("/AppLogger.kt")
        }

        val violations = mutableListOf<String>()
        val dispatchersRegex = Regex("""\bDispatchers\.(IO|Default|Main|Unconfined)\b""")

        for (file in prodFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                if (dispatchersRegex.containsMatchIn(line)) {
                    val relativePath = file.relativeTo(projectRoot).path.replace('\\', '/')
                    violations.add("Direct Dispatchers usage found in production file: $relativePath:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Direct Dispatchers usage is forbidden outside DispatchersModule and AppLogger:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `production code does not use GlobalScope`() {
        val prodFiles = findProductionKtFiles()
        val violations = mutableListOf<String>()
        val globalScopeRegex = Regex("""\bGlobalScope\b""")

        for (file in prodFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                if (globalScopeRegex.containsMatchIn(line)) {
                    val relativePath = file.relativeTo(projectRoot).path.replace('\\', '/')
                    violations.add("GlobalScope usage found in production file: $relativePath:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("GlobalScope usage is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `production code does not use runCatching in suspend context`() {
        val prodFiles = findProductionKtFiles()
        val violations = mutableListOf<String>()
        val suspendFunRegex = Regex("""\bsuspend\s+fun\b""")
        val runCatchingRegex = Regex("""\brunCatching\b""")

        for (file in prodFiles) {
            val lines = file.readLines()
            var insideSuspendFun = false
            var braceDepth = 0
            var suspendFunBraceDepth = 0

            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue

                if (suspendFunRegex.containsMatchIn(line)) {
                    insideSuspendFun = true
                    suspendFunBraceDepth = braceDepth
                }

                if (insideSuspendFun && runCatchingRegex.containsMatchIn(line)) {
                    val relativePath = file.relativeTo(projectRoot).path.replace('\\', '/')
                    violations.add("runCatching in suspend context found in production file: $relativePath:${index + 1}: '$line'")
                }

                val openBraces = line.count { it == '{' }
                val closeBraces = line.count { it == '}' }
                braceDepth += openBraces - closeBraces

                if (insideSuspendFun && braceDepth <= suspendFunBraceDepth && (openBraces > 0 || closeBraces > 0)) {
                    if (braceDepth < suspendFunBraceDepth || closeBraces > 0) {
                        insideSuspendFun = false
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("runCatching in suspend context is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `production code does not use Thread sleep`() {
        val prodFiles = findProductionKtFiles()
        val violations = mutableListOf<String>()
        val threadSleepRegex = Regex("""\bThread\.sleep\b""")

        for (file in prodFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                if (threadSleepRegex.containsMatchIn(line)) {
                    val relativePath = file.relativeTo(projectRoot).path.replace('\\', '/')
                    violations.add("Thread.sleep found in production file: $relativePath:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Thread.sleep is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `production code does not use println or print`() {
        val prodFiles = findProductionKtFiles().filter { file ->
            val path = file.absolutePath.replace('\\', '/')
            !path.endsWith("/AppLogger.kt")
        }

        val violations = mutableListOf<String>()
        val printRegex = Regex("""\b(println|print)\s*\(""")

        for (file in prodFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import")) continue
                if (printRegex.containsMatchIn(line)) {
                    val relativePath = file.relativeTo(projectRoot).path.replace('\\', '/')
                    violations.add("println/print usage found in production file: $relativePath:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("println and print usage is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `production code does not use not-null assertion operator`() {
        val prodFiles = findProductionKtFiles()
        val violations = mutableListOf<String>()

        for (file in prodFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                val codeWithoutComments = line.split("//").first()
                if (codeWithoutComments.contains("!!")) {
                    val relativePath = file.relativeTo(projectRoot).path.replace('\\', '/')
                    violations.add("Not-null assertion (!!) found in production file: $relativePath:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Not-null assertion (!!) is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `core model and core domain modules do not import android packages`() {
        val modelAndDomainFiles = findAllKtFiles().filter { file ->
            val path = file.absolutePath.replace('\\', '/')
            (path.contains("/core/model/src/") || path.contains("/core/domain/src/"))
        }

        val violations = mutableListOf<String>()

        for (file in modelAndDomainFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("import android.")) {
                    val relativePath = file.relativeTo(projectRoot).path.replace('\\', '/')
                    violations.add("android.* import found in domain/model module: $relativePath:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("android.* imports are forbidden in :core:model and :core:domain:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `runBlocking usage must be annotated with RunBlockingBridge`() {
        val allFiles = findAllKtFiles()
        val violations = mutableListOf<String>()
        val runBlockingRegex = Regex("""\b(runBlocking|kotlinx\.coroutines\.runBlocking)\b""")

        for (file in allFiles) {
            val content = file.readText()
            if (!runBlockingRegex.containsMatchIn(content)) continue

            val hasFileAnnotation = content.contains("@file:RunBlockingBridge") || content.contains("@RunBlockingBridge")
            if (hasFileAnnotation) continue

            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import")) continue
                if (runBlockingRegex.containsMatchIn(line)) {
                    val prevLines = lines.subList(maxOf(0, index - 5), index).joinToString("\n")
                    if (!prevLines.contains("@RunBlockingBridge")) {
                        val relativePath = file.relativeTo(projectRoot).path.replace('\\', '/')
                        violations.add("Unsanctioned runBlocking found in file: $relativePath:${index + 1}: '$line'")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("runBlocking is forbidden without explicit @RunBlockingBridge annotation:\n" + violations.joinToString("\n"))
        }
    }
}
