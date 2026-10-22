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

    /**
     * Determines whether a SVG pathData subpath represents an axis-aligned rectangle.
     *
     * @param subpath SVG pathData string snippet representing a single subpath.
     * @return `true` if the subpath parses into an axis-aligned rectangle with 4 vertices.
     */
    private fun isAxisAlignedRectangleSubpath(subpath: String): Boolean {
        val tokens = tokenizeSubpath(subpath)
        val rawVertices = extractSubpathVertices(tokens) ?: return false
        val normalizedVertices = normalizeVertices(rawVertices) ?: return false
        return isAxisAlignedRectangle(normalizedVertices)
    }

    /**
     * Tokenizes a subpath string into command letters and numeric tokens.
     *
     * @param subpath SVG pathData snippet.
     * @return List of command letter tokens and numeric coordinate tokens.
     */
    private fun tokenizeSubpath(subpath: String): List<String> {
        val commandRegex = Regex("""([MmsScCqQtTaAhHvVzZ])|([-+]?\d*\.?\d+(?:[eE][-+]?\d+)?)""")
        return commandRegex.findAll(subpath).map { it.value }.toList()
    }

    /**
     * State container for SVG path traversal cursor position and active command.
     */
    private data class PathCursor(
        val x: Float = 0f,
        val y: Float = 0f,
        val startX: Float = 0f,
        val startY: Float = 0f,
        val currentCmd: Char = ' ',
    )

    /**
     * Result of executing a single path command step.
     */
    private data class StepResult(
        val newIdx: Int,
        val cursor: PathCursor,
    )

    /**
     * Traverses subpath tokens and extracts rounded 2D vertex coordinates.
     *
     * Supports line drawing commands: M/m, L/l, H/h, V/v, Z/z. Unsupported curve
     * commands return `null`.
     *
     * @param tokens Tokenized subpath string.
     * @return List of rounded vertex pairs or `null` if subpath contains non-rectangular commands.
     */
    private fun extractSubpathVertices(tokens: List<String>): List<Pair<Float, Float>>? {
        if (tokens.isEmpty()) return null
        var cursor = PathCursor()
        val vertices = mutableListOf<Pair<Float, Float>>()
        var idx = 0

        while (idx < tokens.size) {
            val token = tokens[idx]
            if (token.length == 1 && token[0].isLetter()) {
                val cmd = token[0]
                idx++
                if (cmd == 'Z' || cmd == 'z') {
                    cursor = cursor.copy(x = cursor.startX, y = cursor.startY, currentCmd = cmd)
                } else {
                    cursor = cursor.copy(currentCmd = cmd)
                }
            } else {
                val step = executeCommandStep(tokens, idx, cursor) ?: return null
                idx = step.newIdx
                cursor = step.cursor
                vertices.add(roundCoord(cursor.x) to roundCoord(cursor.y))
            }
        }
        return vertices
    }

    private fun roundCoord(value: Float): Float = Math.round(value * 100f) / 100f

    /**
     * Executes a single command token using the current active command letter.
     *
     * @param tokens Subpath token list.
     * @param idx Current index in token list.
     * @param cursor Current path cursor state.
     * @return Updated index and cursor state, or `null` if command is invalid or unsupported.
     */
    private fun executeCommandStep(
        tokens: List<String>,
        idx: Int,
        cursor: PathCursor,
    ): StepResult? {
        val nextVal = tokens[idx].toFloatOrNull() ?: return null
        return when (cursor.currentCmd) {
            'M', 'm', 'L', 'l' -> handleMoveOrLine(tokens, idx, cursor, nextVal)
            'H' -> StepResult(idx + 1, cursor.copy(x = nextVal))
            'h' -> StepResult(idx + 1, cursor.copy(x = cursor.x + nextVal))
            'V' -> StepResult(idx + 1, cursor.copy(y = nextVal))
            'v' -> StepResult(idx + 1, cursor.copy(y = cursor.y + nextVal))
            else -> null
        }
    }

    private fun handleMoveOrLine(
        tokens: List<String>,
        idx: Int,
        cursor: PathCursor,
        nextVal: Float,
    ): StepResult? {
        val dy = tokens.getOrNull(idx + 1)?.toFloatOrNull() ?: return null
        val isAbs = cursor.currentCmd == 'M' || cursor.currentCmd == 'L'
        val isMove = cursor.currentCmd == 'M' || cursor.currentCmd == 'm'
        val newX = if (isAbs) nextVal else cursor.x + nextVal
        val newY = if (isAbs) dy else cursor.y + dy
        val nextCmd = if (isMove) (if (isAbs) 'L' else 'l') else cursor.currentCmd
        val startX = if (isMove) newX else cursor.startX
        val startY = if (isMove) newY else cursor.startY
        return StepResult(
            newIdx = idx + 2,
            cursor =
                PathCursor(
                    x = newX,
                    y = newY,
                    startX = startX,
                    startY = startY,
                    currentCmd = nextCmd,
                ),
        )
    }

    /**
     * Normalizes vertex sequence by removing adjacent duplicates and closing vertex.
     *
     * @param vertices Raw extracted subpath vertices.
     * @return List of exactly 4 distinct corner vertices, or `null` if count is not 4.
     */
    private fun normalizeVertices(vertices: List<Pair<Float, Float>>): List<Pair<Float, Float>>? {
        val distinct = mutableListOf<Pair<Float, Float>>()
        for (v in vertices) {
            if (distinct.isEmpty() || distinct.last() != v) {
                distinct.add(v)
            }
        }
        if (distinct.size > 1 && distinct.first() == distinct.last()) {
            distinct.removeAt(distinct.size - 1)
        }
        return if (distinct.size == 4) distinct else null
    }

    /**
     * Checks if 4 normalized corner vertices form an axis-aligned rectangle.
     *
     * @param corners Exactly 4 normalized vertices.
     * @return `true` if vertices contain exactly 2 distinct X coordinates and 2 distinct Y coordinates.
     */
    private fun isAxisAlignedRectangle(corners: List<Pair<Float, Float>>): Boolean {
        val xVals = corners.map { it.first }.toSet()
        val yVals = corners.map { it.second }.toSet()
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
