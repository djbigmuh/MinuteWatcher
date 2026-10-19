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

    private fun extractGlyphs(doc: Document): List<String> {
        val pathNodes = doc.getElementsByTagName("path")
        val glyphs = mutableListOf<String>()
        for (i in 0 until pathNodes.length) {
            val pathEl = pathNodes.item(i) as Element
            if (i > 0) {
                val pathData = getAndroidAttr(pathEl, "pathData")
                if (pathData.isNotEmpty()) {
                    glyphs.add(normalizePathData(pathData))
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

    private fun normalizePathData(pathData: String): String = pathData.replace(Regex("\\s+"), " ").trim().uppercase()

    private fun isPlaceholderGlyph(glyphs: List<String>): Boolean {
        if (glyphs.isEmpty()) return false
        return glyphs.any { isSinglePlaceholderPath(it.uppercase()) }
    }

    private fun isSinglePlaceholderPath(upper: String): Boolean {
        val isBox = upper.contains("0,0 H48") || upper.contains("H48 V48") || upper.contains("V48 H0")
        if (!isBox) return false
        val containsHAndV = upper.startsWith("M") && upper.contains("H") && upper.contains("V")
        val containsLAndM = upper.startsWith("M") && upper.count { it == 'L' || it == 'M' } in 4..5
        return containsHAndV || containsLAndM
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
