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

    /**
     * Checks if any glyph in the list is a banned placeholder marker composed solely
     * of axis-aligned rectangles (solid rectangle or rectangular ring).
     */
    private fun isPlaceholderGlyph(glyphs: List<String>): Boolean {
        if (glyphs.isEmpty()) return false
        return glyphs.any { isAxisAlignedRectangleGlyph(it) }
    }

    private fun isAxisAlignedRectangleGlyph(pathData: String): Boolean {
        val subpaths = splitIntoSubpaths(pathData)
        if (subpaths.isEmpty()) return false
        return subpaths.all { isAxisAlignedRectangleSubpath(it) }
    }

    private fun splitIntoSubpaths(pathData: String): List<String> {
        val regex = Regex("""(?=[MmsScCqQtTaAhHvVzZ])""")
        val tokens = pathData.split(regex).map { it.trim() }.filter { it.isNotEmpty() }
        val subpaths = mutableListOf<String>()
        var currentSubpath = StringBuilder()

        for (token in tokens) {
            val cmd = token[0]
            if ((cmd == 'M' || cmd == 'm') && currentSubpath.isNotEmpty()) {
                subpaths.add(currentSubpath.toString().trim())
                currentSubpath = StringBuilder()
            }
            currentSubpath.append(" ").append(token)
        }
        if (currentSubpath.isNotEmpty()) {
            subpaths.add(currentSubpath.toString().trim())
        }
        return subpaths
    }

    private fun isAxisAlignedRectangleSubpath(subpath: String): Boolean {
        val commandRegex = Regex("""([MmsScCqQtTaAhHvVzZ])|([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)""")
        val matches = commandRegex.findAll(subpath).map { it.value }.toList()
        if (matches.isEmpty()) return false

        var curX = 0f
        var curY = 0f
        var startX = 0f
        var startY = 0f
        var currentCmd = ' '

        val vertices = mutableListOf<Pair<Float, Float>>()

        fun roundCoord(v: Float): Float = Math.round(v * 100f) / 100f

        var idx = 0
        while (idx < matches.size) {
            val token = matches[idx]
            if (token.length == 1 && token[0].isLetter()) {
                currentCmd = token[0]
                idx++
                if (currentCmd == 'Z' || currentCmd == 'z') {
                    curX = startX
                    curY = startY
                }
            } else {
                when (currentCmd) {
                    'M', 'm' -> {
                        val dx = token.toFloatOrNull() ?: return false
                        val dy = matches.getOrNull(idx + 1)?.toFloatOrNull() ?: return false
                        idx += 2
                        if (currentCmd == 'M') {
                            curX = dx
                            curY = dy
                        } else {
                            curX += dx
                            curY += dy
                        }
                        startX = curX
                        startY = curY
                        vertices.add(roundCoord(curX) to roundCoord(curY))
                        currentCmd = if (currentCmd == 'M') 'L' else 'l'
                    }
                    'L', 'l' -> {
                        val dx = token.toFloatOrNull() ?: return false
                        val dy = matches.getOrNull(idx + 1)?.toFloatOrNull() ?: return false
                        idx += 2
                        if (currentCmd == 'L') {
                            curX = dx
                            curY = dy
                        } else {
                            curX += dx
                            curY += dy
                        }
                        vertices.add(roundCoord(curX) to roundCoord(curY))
                    }
                    'H', 'h' -> {
                        val v = token.toFloatOrNull() ?: return false
                        idx++
                        if (currentCmd == 'H') curX = v else curX += v
                        vertices.add(roundCoord(curX) to roundCoord(curY))
                    }
                    'V', 'v' -> {
                        val v = token.toFloatOrNull() ?: return false
                        idx++
                        if (currentCmd == 'V') curY = v else curY += v
                        vertices.add(roundCoord(curX) to roundCoord(curY))
                    }
                    else -> return false
                }
            }
        }

        val distinct = mutableListOf<Pair<Float, Float>>()
        for (v in vertices) {
            if (distinct.isEmpty() || distinct.last() != v) {
                distinct.add(v)
            }
        }
        if (distinct.size > 1 && distinct.first() == distinct.last()) {
            distinct.removeAt(distinct.size - 1)
        }

        if (distinct.size != 4) return false

        val xVals = distinct.map { it.first }.toSet()
        val yVals = distinct.map { it.second }.toSet()

        return xVals.size == 2 && yVals.size == 2
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
