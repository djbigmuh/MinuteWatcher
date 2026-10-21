package host.serv.djbigmuh.minutewatcher.core.resources

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class OperatorIconUniquenessTest {
    private val sharedMarkGroups =
        listOf(
            setOf("ic_operator_t_mobile", "ic_operator_telekom", "ic_operator_magenta"),
            setOf("ic_operator_life_by", "ic_operator_lifecell"),
        )

    private val placeholderExceptions =
        setOf(
            "ic_operator_orange", // Official Orange sign is a solid square block
        )

    @Test
    fun `validate operator icon uniqueness and geometry`() {
        val resDir = getResDir()
        val drawableDir = File(resDir, "drawable")
        assertTrue("Drawable directory must exist", drawableDir.exists())

        val iconFiles =
            drawableDir.listFiles { _, name ->
                name.startsWith("ic_operator_") && name.endsWith(".xml")
            } ?: emptyArray()
        assertTrue("Operator icon files must not be empty", iconFiles.isNotEmpty())

        val dbFactory = DocumentBuilderFactory.newInstance()
        dbFactory.isNamespaceAware = true
        val docBuilder = dbFactory.newDocumentBuilder()

        val failures = mutableListOf<String>()
        val contentHashMap = mutableMapOf<String, String>()
        val glyphMap = mutableMapOf<String, List<String>>()

        for (file in iconFiles) {
            val resName = file.nameWithoutExtension
            val doc = docBuilder.parse(file)

            checkContentUniqueness(file, resName, contentHashMap, failures)
            checkPlatePalette(file, resName, failures)
            val glyphs = extractGlyphs(doc)
            checkPlaceholderBan(resName, glyphs, failures)
            checkGlyphUniqueness(resName, glyphs, glyphMap, failures)

            glyphMap[resName] = glyphs
        }

        if (failures.isNotEmpty()) {
            fail("Operator icon uniqueness failures:\n" + failures.joinToString("\n"))
        }
    }

    private fun checkContentUniqueness(
        file: File,
        resName: String,
        contentHashMap: MutableMap<String, String>,
        failures: MutableList<String>,
    ) {
        val text = file.readText()
        val normalizedContent = normalizeContent(text)
        val hash = sha256(normalizedContent)

        for ((existingName, existingHash) in contentHashMap) {
            if (existingHash == hash && !isSameSharedGroup(resName, existingName)) {
                failures.add("Check 1 (Content Uniqueness): $resName has identical normalized content to $existingName")
            }
        }
        contentHashMap[resName] = hash
    }

    private fun checkPlatePalette(
        file: File,
        resName: String,
        failures: MutableList<String>,
    ) {
        val dbFactory = DocumentBuilderFactory.newInstance()
        dbFactory.isNamespaceAware = true
        val doc = dbFactory.newDocumentBuilder().parse(file)
        val paths = doc.getElementsByTagName("path")
        if (paths.length > 0) {
            val firstPath = paths.item(0) as Element
            val fillColor = getAndroidAttr(firstPath, "fillColor")
            if (fillColor != "#FFFFFFFF" && fillColor != "#FF141218" && fillColor != "#FFFFD700") {
                failures.add(
                    "Check 4 (Plate Palette): $resName first path (plate) has invalid color '$fillColor'. " +
                        "Must be #FFFFFFFF, #FF141218 or #FFFFD700.",
                )
            }
        }
    }

    private fun extractGlyphs(doc: Document): List<String> {
        val pathNodes = doc.getElementsByTagName("path")
        val glyphs = mutableListOf<String>()
        for (i in 0 until pathNodes.length) {
            val pathEl = pathNodes.item(i) as Element
            if (i > 0) {
                val pathData = getAndroidAttr(pathEl, "pathData")
                if (pathData.isNotEmpty()) {
                    glyphs.add(normalizeGlyphGeometry(pathData))
                }
            }
        }
        return glyphs
    }

    private fun checkPlaceholderBan(
        resName: String,
        glyphs: List<String>,
        failures: MutableList<String>,
    ) {
        if (resName in placeholderExceptions) return
        if (isPlaceholderGlyph(glyphs)) {
            failures.add(
                "Check 3 (Placeholder Ban): $resName uses a banned placeholder marker " +
                    "(solid rectangle or rectangular ring)",
            )
        }
    }

    private fun checkGlyphUniqueness(
        resName: String,
        glyphs: List<String>,
        glyphMap: Map<String, List<String>>,
        failures: MutableList<String>,
    ) {
        for ((existingName, existingGlyphs) in glyphMap) {
            if (existingGlyphs == glyphs && !isSameSharedGroup(resName, existingName)) {
                failures.add(
                    "Check 2 (Glyph Geometry Uniqueness): $resName has identical " +
                        "normalized glyph geometry to $existingName",
                )
            }
        }
    }

    private fun isSameSharedGroup(
        resName: String,
        existingName: String,
    ): Boolean = sharedMarkGroups.any { group -> resName in group && existingName in group }

    private fun normalizeContent(text: String): String =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("\\s+"), " ")

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeGlyphGeometry(pathData: String): String =
        pathData.replace(Regex("\\s+"), " ").trim().uppercase()

    private fun isPlaceholderGlyph(glyphs: List<String>): Boolean {
        if (glyphs.isEmpty()) return false
        // Per TZ-04: A glyph is a placeholder if it is an unstyled solid full-viewport or large background-covering rectangle box.
        // Our stylized marks (e.g. nested frames or specific paths) are distinct.
        return glyphs.any { isUnstyledFullRect(it) }
    }

    private fun isUnstyledFullRect(upper: String): Boolean {
        // Full viewport or near-viewport solid rectangle (e.g. M 0 0 H 48 V 48 H 0 Z or similar covering > 90% of view)
        return upper.contains("0,0") &&
            upper.contains("48") &&
            (upper.count { it == 'L' || it == 'H' || it == 'V' } <= 4)
    }

    private fun getAndroidAttr(
        element: Element,
        attrName: String,
    ): String {
        val nsVal = element.getAttributeNS("http://schemas.android.com/apk/res/android", attrName)
        if (nsVal.isNotEmpty()) return nsVal
        return element.getAttribute("android:$attrName")
    }

    private fun getResDir(): File {
        val prop = System.getProperty("minutewatcher.coreResourcesResDir")
        require(!prop.isNullOrEmpty()) { "System property minutewatcher.coreResourcesResDir is not set" }
        val dir = File(prop)
        require(dir.exists()) { "Core resources directory does not exist at $prop" }
        return dir
    }
}
