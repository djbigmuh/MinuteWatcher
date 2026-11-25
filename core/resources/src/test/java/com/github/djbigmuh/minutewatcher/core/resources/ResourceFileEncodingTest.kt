package com.github.djbigmuh.minutewatcher.core.resources

import com.github.djbigmuh.minutewatcher.core.resources.testing.ProjectPaths
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class ResourceFileEncodingTest {
    @Test
    fun `verify resource and metadata file encodings`() {
        val root = ProjectPaths.root
        val filesToCheck = collectFilesToCheck(root)

        assertTrue("Files to check must not be empty", filesToCheck.isNotEmpty())

        val errors = mutableListOf<String>()

        for (file in filesToCheck) {
            checkFileEncoding(file)?.let { errors.add(it) }
        }

        if (errors.isNotEmpty()) {
            org.junit.Assert.fail("Encoding checks failed:\n" + errors.joinToString("\n"))
        }
    }

    private fun collectFilesToCheck(root: File): List<File> {
        val filesToCheck = mutableListOf<File>()

        val resDir = File(root, "core/resources/src/main/res")
        if (resDir.exists()) {
            resDir.listFiles { dir -> dir.name.startsWith("values") }?.forEach { valuesDir ->
                if (valuesDir.isDirectory) {
                    valuesDir.walkTopDown().filter { it.isFile && it.extension.lowercase() == "xml" }.forEach {
                        filesToCheck.add(it)
                    }
                }
            }
        }

        val fastlaneDir = File(root, "fastlane/metadata")
        if (fastlaneDir.exists()) {
            fastlaneDir.walkTopDown().filter { it.isFile && it.extension.lowercase() == "txt" }.forEach {
                filesToCheck.add(it)
            }
        }

        root.listFiles { file -> file.isFile && file.name.lowercase().endsWith(".md") }?.forEach {
            filesToCheck.add(it)
        }

        return filesToCheck
    }

    private fun checkFileEncoding(file: File): String? {
        val bytes = file.readBytes()

        if (hasBom(bytes)) {
            return "File ${file.absolutePath}: line 1 contains UTF-8 BOM (EF BB BF)"
        }

        val text = decodeUtf8(bytes) ?: return "File ${file.absolutePath}: line 1 malformed UTF-8"

        return checkDoubleEncoding(file, text)
    }

    private fun hasBom(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()

    @Suppress("SwallowedException")
    private fun decodeUtf8(bytes: ByteArray): String? {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    @Suppress("SwallowedException")
    private fun checkDoubleEncoding(
        file: File,
        text: String,
    ): String? {
        val hasC0toFF = text.any { it in '\u00C0'..'\u00FF' }
        val allLeFF = text.all { it <= '\u00FF' }
        if (!hasC0toFF || !allLeFF) {
            return null
        }

        val isoBytes = text.toByteArray(Charsets.ISO_8859_1)
        val utf8Decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decodedFromIso =
            try {
                utf8Decoder.decode(ByteBuffer.wrap(isoBytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                null
            }

        if (decodedFromIso != null && decodedFromIso != text) {
            var targetLine = 1
            var targetSnippet = ""
            text.lineSequence().forEachIndexed { index, line ->
                if (targetSnippet.isEmpty()) {
                    for (ch in line) {
                        if (ch in '\u00C0'..'\u00FF') {
                            targetLine = index + 1
                            targetSnippet = line
                            return@forEachIndexed
                        }
                    }
                }
            }
            return "File ${file.absolutePath}: line $targetLine has double encoding. Snippet: $targetSnippet"
        }
        return null
    }
}
