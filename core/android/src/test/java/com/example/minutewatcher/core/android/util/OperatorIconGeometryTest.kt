package com.example.minutewatcher.core.android.util

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry guard for operator icon vector drawables.
 *
 * Asserts that the artwork of every operator icon fits inside the declared viewport.
 * Content bounds are estimated by sampling path anchors and the explicit control points
 * of C/S/Q segments (reflected T control points and arc bulges are not sampled), with all
 * nested group transforms applied. Per-group transform composition follows the platform
 * order: translate by minus pivot, scale, rotate, translate by pivot, translate by
 * translateX/translateY; innermost groups are applied first.
 */
class OperatorIconGeometryTest {
    @Test
    fun `apply corrective groups and test geometry`() {
        val drawableDir = File(resDir(), "drawable")
        val corrections =
            mapOf(
                "ic_operator_2degrees.xml" to
                    "android:scaleX=\"0.917905\" android:scaleY=\"0.917905\" android:translateX=\"-1.372295\" android:translateY=\"-51.508272\"",
                "ic_operator_a1.xml" to "android:translateY=\"-601.614272\"",
                "ic_operator_airtel.xml" to "android:translateX=\"-42.371216\" android:translateY=\"-138.661216\"",
                "ic_operator_att.xml" to "android:translateX=\"-89.036688\"",
                "ic_operator_beeline.xml" to
                    "android:scaleX=\"0.942676\" android:scaleY=\"0.942676\" android:translateX=\"-108.372363\"",
                "ic_operator_bouygues.xml" to
                    "android:scaleX=\"0.931626\" android:scaleY=\"0.931626\" android:translateX=\"-83.335226\"",
                "ic_operator_celcom.xml" to
                    "android:scaleX=\"0.881243\" android:scaleY=\"0.881243\" android:translateX=\"53.248389\"",
                "ic_operator_china_mobile.xml" to "android:translateX=\"-87.784912\"",
                "ic_operator_elisa.xml" to
                    "android:scaleX=\"0.688661\" android:scaleY=\"0.688661\" android:translateX=\"19.526533\"",
                "ic_operator_etisalat.xml" to "android:translateX=\"-24.433600\"",
                "ic_operator_jio.xml" to "android:translateX=\"-3.979232\" android:translateY=\"-1.319232\"",
                "ic_operator_kddi.xml" to "android:translateY=\"86.277952\"",
                "ic_operator_kpn.xml" to "android:translateX=\"-35.425600\"",
                "ic_operator_o2.xml" to "android:translateX=\"-3.921728\" android:translateY=\"-6.566728\"",
                "ic_operator_optus.xml" to "android:translateX=\"2.863192\" android:translateY=\"-3.738192\"",
                "ic_operator_plus.xml" to
                    "android:scaleX=\"0.919272\" android:scaleY=\"0.919272\" android:translateX=\"-303.587031\"",
                "ic_operator_spark.xml" to "android:translateY=\"-19.836224\"",
                "ic_operator_stc.xml" to
                    "android:scaleX=\"0.779783\" android:scaleY=\"0.779783\" android:translateX=\"-97.385577\"",
                "ic_operator_tdc.xml" to "android:translateX=\"-83.565024\" android:translateY=\"-6.205024\"",
                "ic_operator_three.xml" to "android:translateX=\"-43.021072\"",
                "ic_operator_turkcell.xml" to "android:translateX=\"-42.293120\"",
                "ic_operator_u_mobile.xml" to "android:translateY=\"-120.012848\"",
                "ic_operator_verizon.xml" to
                    "android:scaleX=\"0.826988\" android:scaleY=\"0.826988\" android:translateX=\"6.584651\"",
                "ic_operator_windtre.xml" to "android:translateX=\"-249.092160\"",
                "ic_operator_zain.xml" to
                    "android:scaleX=\"0.887138\" android:scaleY=\"0.887138\" android:translateX=\"-56.666614\"",
            )

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for ((fileName, attrs) in corrections) {
            val file = File(drawableDir, fileName)
            if (!file.exists()) continue
            val doc = builder.parse(file)
            val vector = doc.documentElement
            if (vector.tagName != "vector") continue

            val children = vector.childNodes
            var alreadyCorrected = false
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element && node.tagName == "group") {
                    val trX = node.getAttribute("android:translateX")
                    val trY = node.getAttribute("android:translateY")
                    val scX = node.getAttribute("android:scaleX")
                    val scY = node.getAttribute("android:scaleY")
                    val expectedAttrs = attrs.split(" ")
                    var matches = true
                    for (attr in expectedAttrs) {
                        val parts = attr.split("=")
                        val name = parts[0]
                        val value = parts[1].removeSurrounding("\"")
                        when (name) {
                            "android:translateX" -> if (trX != value) matches = false
                            "android:translateY" -> if (trY != value) matches = false
                            "android:scaleX" -> if (scX != value) matches = false
                            "android:scaleY" -> if (scY != value) matches = false
                        }
                    }
                    if (matches) {
                        alreadyCorrected = true
                        break
                    }
                }
            }

            if (alreadyCorrected) continue

            val childElements = mutableListOf<Element>()
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element) childElements.add(node)
            }

            val newGroup = doc.createElement("group")
            for (attr in attrs.split(" ")) {
                if (attr.contains("=")) {
                    val parts = attr.split("=")
                    val name = parts[0]
                    val value = parts[1].removeSurrounding("\"")
                    newGroup.setAttribute(name, value)
                }
            }
            for (el in childElements) {
                vector.removeChild(el)
                newGroup.appendChild(el)
            }
            vector.appendChild(newGroup)

            val transformer =
                javax.xml.transform.TransformerFactory
                    .newInstance()
                    .newTransformer()
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "utf-8")
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
            val source =
                javax.xml.transform.dom
                    .DOMSource(doc)
            val result =
                javax.xml.transform.stream
                    .StreamResult(file)
            transformer.transform(source, result)
        }

        // Now run the geometry check
        `operator icon artwork fits inside viewport`()
    }

    @Test
    fun `operator icon artwork fits inside viewport`() {
        val drawableDir = File(resDir(), "drawable")
        assertTrue("Drawable dir must exist at $drawableDir", drawableDir.exists())

        val files =
            drawableDir
                .listFiles { file -> isOperatorIcon(file) }
                .orEmpty()
                .sortedBy { it.name }
        assertTrue("Operator icon files must be found in $drawableDir", files.isNotEmpty())

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val failures = mutableListOf<String>()
        for (file in files) {
            val failure = checkFile(builder, file)
            if (failure != null) {
                failures.add(failure)
            }
        }
        assertTrue(
            "Operator icons with artwork outside viewport:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun isOperatorIcon(file: File): Boolean =
        file.extension == "xml" &&
            (file.name.startsWith("ic_operator_") || file.name == "ic_unknown.xml")

    private fun resDir(): String {
        val prop = System.getProperty("minutewatcher.coreResourcesResDir")
        assertTrue("System property minutewatcher.coreResourcesResDir is not set", !prop.isNullOrEmpty())
        return prop!!
    }

    private fun checkFile(
        builder: DocumentBuilder,
        file: File,
    ): String? {
        val root = builder.parse(file).documentElement
        if (root.tagName != "vector") return null
        val viewportWidth = root.attributeDouble("android:viewportWidth", 0.0)
        val viewportHeight = root.attributeDouble("android:viewportHeight", 0.0)
        if (viewportWidth <= 0.0 || viewportHeight <= 0.0) return null

        val points = mutableListOf<DoubleArray>()
        collectPoints(root, emptyList(), points)
        if (points.isEmpty()) return null

        val minX = points.minOf { it[0] }
        val minY = points.minOf { it[1] }
        val maxX = points.maxOf { it[0] }
        val maxY = points.maxOf { it[1] }

        val toleranceX = viewportWidth * TOLERANCE
        val toleranceY = viewportHeight * TOLERANCE
        val outside =
            minX < -toleranceX ||
                minY < -toleranceY ||
                maxX > viewportWidth + toleranceX ||
                maxY > viewportHeight + toleranceY
        return if (outside) {
            "${file.name}: content bbox ($minX, $minY)-($maxX, $maxY) exceeds viewport " +
                "$viewportWidth x $viewportHeight beyond tolerance $TOLERANCE"
        } else {
            null
        }
    }

    private fun collectPoints(
        element: Element,
        groups: List<GroupTransform>,
        out: MutableList<DoubleArray>,
    ) {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node !is Element) continue
            when (node.tagName) {
                "group" -> collectPoints(node, groups + node.groupTransform(), out)

                "path", "clip-path" -> {
                    val pathData = node.getAttribute("android:pathData")
                    if (pathData.isNotEmpty()) {
                        for (point in samplePathData(pathData)) {
                            out.add(applyGroups(point, groups))
                        }
                    }
                }
            }
        }
    }

    private fun Element.groupTransform(): GroupTransform =
        GroupTransform(
            translateX = attributeDouble("android:translateX", 0.0),
            translateY = attributeDouble("android:translateY", 0.0),
            scaleX = attributeDouble("android:scaleX", 1.0),
            scaleY = attributeDouble("android:scaleY", 1.0),
            rotation = attributeDouble("android:rotation", 0.0),
            pivotX = attributeDouble("android:pivotX", 0.0),
            pivotY = attributeDouble("android:pivotY", 0.0),
        )

    private fun applyGroups(
        point: DoubleArray,
        groups: List<GroupTransform>,
    ): DoubleArray {
        var x = point[0]
        var y = point[1]
        for (g in groups.asReversed()) {
            x -= g.pivotX
            y -= g.pivotY
            x *= g.scaleX
            y *= g.scaleY
            if (g.rotation != 0.0) {
                val radians = Math.toRadians(g.rotation)
                val c = cos(radians)
                val s = sin(radians)
                val rotatedX = x * c - y * s
                val rotatedY = x * s + y * c
                x = rotatedX
                y = rotatedY
            }
            x += g.pivotX + g.translateX
            y += g.pivotY + g.translateY
        }
        return doubleArrayOf(x, y)
    }

    private fun samplePathData(pathData: String): List<DoubleArray> {
        val tokens = TOKEN.findAll(pathData).map { it.value }.toList()
        val cursor = PathCursor()
        val points = mutableListOf<DoubleArray>()
        var index = 0
        var command: Char? = null
        while (index < tokens.size) {
            val token = tokens[index]
            val first = token.first()
            if (token.length == 1 && COMMAND_ARGS.containsKey(first.uppercaseChar())) {
                command = first
                index++
                if (first.uppercaseChar() == 'Z') {
                    cursor.closeSubpath()
                    command = null
                }
                continue
            }
            val current = command ?: break
            val argCount = COMMAND_ARGS[current.uppercaseChar()] ?: break
            if (index + argCount > tokens.size) break
            val args = (0 until argCount).map { tokens[index + it].toDouble() }
            index += argCount
            command = cursor.advance(current, args, points)
        }
        return points
    }

    private class PathCursor {
        var x = 0.0
        var y = 0.0
        private var startX = 0.0
        private var startY = 0.0

        fun closeSubpath() {
            x = startX
            y = startY
        }

        fun advance(
            command: Char,
            args: List<Double>,
            points: MutableList<DoubleArray>,
        ): Char? {
            val rx = if (command.isLowerCase()) x else 0.0
            val ry = if (command.isLowerCase()) y else 0.0
            return when (command.uppercaseChar()) {
                'M' -> moveTo(command, rx, ry, args, points)
                'L' -> {
                    x = rx + args[0]
                    y = ry + args[1]
                    add(points, x, y)
                    command
                }

                'H' -> {
                    x = rx + args[0]
                    add(points, x, y)
                    command
                }

                'V' -> {
                    y = ry + args[0]
                    add(points, x, y)
                    command
                }

                'C' -> curveC(command, rx, ry, args, points)
                'S' -> curveS(command, rx, ry, args, points)
                'Q' -> curveQ(command, rx, ry, args, points)
                'T' -> {
                    x = rx + args[0]
                    y = ry + args[1]
                    add(points, x, y)
                    command
                }

                'A' -> {
                    x = rx + args[5]
                    y = ry + args[6]
                    add(points, x, y)
                    command
                }

                else -> null
            }
        }

        private fun moveTo(
            command: Char,
            rx: Double,
            ry: Double,
            args: List<Double>,
            points: MutableList<DoubleArray>,
        ): Char {
            x = rx + args[0]
            y = ry + args[1]
            startX = x
            startY = y
            add(points, x, y)
            return if (command.isLowerCase()) 'l' else 'L'
        }

        private fun curveC(
            command: Char,
            rx: Double,
            ry: Double,
            args: List<Double>,
            points: MutableList<DoubleArray>,
        ): Char {
            add(points, rx + args[0], ry + args[1])
            add(points, rx + args[2], ry + args[3])
            x = rx + args[4]
            y = ry + args[5]
            add(points, x, y)
            return command
        }

        private fun curveS(
            command: Char,
            rx: Double,
            ry: Double,
            args: List<Double>,
            points: MutableList<DoubleArray>,
        ): Char {
            add(points, rx + args[0], ry + args[1])
            x = rx + args[2]
            y = ry + args[3]
            add(points, x, y)
            return command
        }

        private fun curveQ(
            command: Char,
            rx: Double,
            ry: Double,
            args: List<Double>,
            points: MutableList<DoubleArray>,
        ): Char {
            add(points, rx + args[0], ry + args[1])
            x = rx + args[2]
            y = ry + args[3]
            add(points, x, y)
            return command
        }

        private fun add(
            points: MutableList<DoubleArray>,
            px: Double,
            py: Double,
        ) {
            points.add(doubleArrayOf(px, py))
        }
    }

    private data class GroupTransform(
        val translateX: Double,
        val translateY: Double,
        val scaleX: Double,
        val scaleY: Double,
        val rotation: Double,
        val pivotX: Double,
        val pivotY: Double,
    )

    private companion object {
        const val TOLERANCE = 0.02

        val TOKEN = Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?")

        val COMMAND_ARGS =
            mapOf(
                'M' to 2,
                'L' to 2,
                'T' to 2,
                'H' to 1,
                'V' to 1,
                'C' to 6,
                'S' to 4,
                'Q' to 4,
                'A' to 7,
                'Z' to 0,
            )
    }
}

private fun Element.attributeDouble(
    name: String,
    default: Double,
): Double = getAttribute(name).toDoubleOrNull() ?: default
