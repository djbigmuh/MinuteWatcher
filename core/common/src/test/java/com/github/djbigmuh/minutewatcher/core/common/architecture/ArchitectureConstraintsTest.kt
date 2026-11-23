package com.github.djbigmuh.minutewatcher.core.common.architecture

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ArchitectureConstraintsTest {
    private fun relativePath(file: File): String = file.relativeTo(projectRoot).path.replace('\\', '/')

    private fun isCodeLine(line: String): Boolean {
        val trimmed = line.trim()
        return !trimmed.startsWith("//") && !trimmed.startsWith("*")
    }

    @Test
    fun `production code does not use direct Dispatchers IO, Default, or Main`() {
        val prodFiles =
            findProductionKtFiles().filter { file ->
                val path = file.absolutePath.replace('\\', '/')
                !path.endsWith("/DispatchersModule.kt") && !path.endsWith("/AppLogger.kt")
            }

        val violations = mutableListOf<String>()
        val dispatchersRegex = Regex("""\bDispatchers\.(IO|Default|Main|Unconfined)\b""")

        for (file in prodFiles) {
            file.readLines().forEachIndexed { index, line ->
                if (isCodeLine(line) && dispatchersRegex.containsMatchIn(line)) {
                    violations.add("Direct Dispatchers usage: ${relativePath(file)}:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Direct Dispatchers usage is forbidden outside DispatchersModule and AppLogger:\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    @Test
    fun `production code does not use GlobalScope`() {
        val violations = mutableListOf<String>()
        val globalScopeRegex = Regex("""\bGlobalScope\b""")

        for (file in findProductionKtFiles()) {
            file.readLines().forEachIndexed { index, line ->
                if (isCodeLine(line) && globalScopeRegex.containsMatchIn(line)) {
                    violations.add("GlobalScope usage: ${relativePath(file)}:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("GlobalScope usage is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `production code does not use runCatching in suspend context`() {
        val violations = mutableListOf<String>()
        for (file in findProductionKtFiles()) {
            checkFileForRunCatchingInSuspend(file, violations)
        }

        if (violations.isNotEmpty()) {
            fail("runCatching in suspend context is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    private fun checkFileForRunCatchingInSuspend(
        file: File,
        violations: MutableList<String>,
    ) {
        val suspendFunRegex = Regex("""\bsuspend\s+fun\b""")
        val runCatchingRegex = Regex("""\brunCatching\b""")
        var insideSuspendFun = false
        var braceDepth = 0
        var suspendFunBraceDepth = 0

        file.readLines().forEachIndexed { index, line ->
            if (isCodeLine(line)) {
                if (suspendFunRegex.containsMatchIn(line)) {
                    insideSuspendFun = true
                    suspendFunBraceDepth = braceDepth
                }

                if (insideSuspendFun && runCatchingRegex.containsMatchIn(line)) {
                    violations.add("runCatching in suspend context: ${relativePath(file)}:${index + 1}: '$line'")
                }

                val openBraces = line.count { it == '{' }
                val closeBraces = line.count { it == '}' }
                braceDepth += openBraces - closeBraces

                if (insideSuspendFun && braceDepth <= suspendFunBraceDepth && openBraces != closeBraces) {
                    insideSuspendFun = false
                }
            }
        }
    }

    @Test
    fun `production code does not use Thread sleep`() {
        val violations = mutableListOf<String>()
        val threadSleepRegex = Regex("""\bThread\.sleep\b""")

        for (file in findProductionKtFiles()) {
            file.readLines().forEachIndexed { index, line ->
                if (isCodeLine(line) && threadSleepRegex.containsMatchIn(line)) {
                    violations.add("Thread.sleep: ${relativePath(file)}:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Thread.sleep is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `production code does not use println or print`() {
        val prodFiles =
            findProductionKtFiles().filter { file ->
                !file.absolutePath.replace('\\', '/').endsWith("/AppLogger.kt")
            }

        val violations = mutableListOf<String>()
        val printRegex = Regex("""\b(println|print)\s*\(""")

        for (file in prodFiles) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (isCodeLine(line) && !trimmed.startsWith("import") && printRegex.containsMatchIn(line)) {
                    violations.add("println/print usage: ${relativePath(file)}:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("println and print usage is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `production code does not use not-null assertion operator`() {
        val violations = mutableListOf<String>()

        for (file in findProductionKtFiles()) {
            file.readLines().forEachIndexed { index, line ->
                if (isCodeLine(line)) {
                    val code = line.split("//").first()
                    if (code.contains("!!")) {
                        violations.add("Not-null assertion (!!): ${relativePath(file)}:${index + 1}: '$line'")
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Not-null assertion (!!) is forbidden in production code:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `core model and core domain modules do not import android packages`() {
        val modelAndDomainFiles =
            findAllKtFiles().filter { file ->
                val path = file.absolutePath.replace('\\', '/')
                path.contains("/core/model/src/") || path.contains("/core/domain/src/")
            }

        val violations = mutableListOf<String>()

        for (file in modelAndDomainFiles) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import android.")) {
                    violations.add("android.* import: ${relativePath(file)}:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("android.* imports are forbidden in :core:model and :core:domain:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `runBlocking usage must be annotated with RunBlockingBridge`() {
        val violations = mutableListOf<String>()
        for (file in findAllKtFiles()) {
            checkFileForSanctionedRunBlocking(file, violations)
        }

        if (violations.isNotEmpty()) {
            fail("runBlocking is forbidden without explicit @RunBlockingBridge:\n" + violations.joinToString("\n"))
        }
    }

    private fun checkFileForSanctionedRunBlocking(
        file: File,
        violations: MutableList<String>,
    ) {
        val content = file.readText()
        val runBlockingRegex = Regex("""\b(runBlocking|kotlinx\.coroutines\.runBlocking)\b""")
        if (!runBlockingRegex.containsMatchIn(content)) return

        val filePkg = "com.github.djbigmuh.minutewatcher.core.common.annotation.RunBlockingBridge"
        if (content.contains("@file:RunBlockingBridge") || content.contains("@file:$filePkg")) return

        val lines = file.readLines()
        for ((index, line) in lines.withIndex()) {
            if (isCodeLine(line) && !line.trim().startsWith("import") && runBlockingRegex.containsMatchIn(line)) {
                val prevText = lines.subList(0, index + 1).joinToString("\n")
                if (!prevText.contains("@RunBlockingBridge") && !prevText.contains("@$filePkg")) {
                    violations.add("Unsanctioned runBlocking: ${relativePath(file)}:${index + 1}: '$line'")
                }
            }
        }
    }

    @Test
    fun `components running on main dispatcher do not execute blocking operations`() {
        val mainComponents =
            findProductionKtFiles().filter { file ->
                val path = file.absolutePath.replace('\\', '/')
                path.contains("/feature/") ||
                    path.contains("/app/") ||
                    file.name.contains("ViewModel") ||
                    file.name.contains("Controller") ||
                    file.name.contains("Screen") ||
                    file.name.contains("Activity")
            }

        val violations = mutableListOf<String>()
        val blockingRegex = Regex("""\b(Thread\.sleep|runBlocking|FileInputStream|FileOutputStream)\b""")

        for (file in mainComponents) {
            file.readLines().forEachIndexed { index, line ->
                if (isCodeLine(line) && blockingRegex.containsMatchIn(line)) {
                    violations.add("Blocking operation on main component: ${relativePath(file)}:${index + 1}: '$line'")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Blocking operations on main dispatcher components are forbidden:\n" + violations.joinToString("\n"))
        }
    }

    companion object {
        private val projectRoot: File by lazy {
            val rootPath =
                System.getProperty("minutewatcher.projectRoot")
                    ?: error("System property minutewatcher.projectRoot is not set")
            File(rootPath)
        }

        private val productionKtFiles: List<File> by lazy {
            projectRoot
                .walkTopDown()
                .filter { file ->
                    if (!file.isFile || file.extension != "kt") return@filter false
                    val path = file.absolutePath.replace('\\', '/')
                    path.contains("/src/main/") && !path.contains("/build/")
                }.toList()
        }

        private val allKtFiles: List<File> by lazy {
            projectRoot
                .walkTopDown()
                .filter { file ->
                    if (!file.isFile || (file.extension != "kt" && file.extension != "kts")) return@filter false
                    val path = file.absolutePath.replace('\\', '/')
                    !path.contains("/build/") && !path.contains("/.gradle/")
                }.toList()
        }
    }

    private fun findProductionKtFiles(): List<File> = productionKtFiles

    private fun findAllKtFiles(): List<File> = allKtFiles
}
