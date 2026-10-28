package host.serv.djbigmuh.minutewatcher.core.android.util

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
        var running = true
        while (index < tokens.size && running) {
            val token = tokens[index]
            val first = token.first()
            if (token.length == 1 && COMMAND_ARGS.containsKey(first.uppercaseChar())) {
                command = first
                index++
                if (first.uppercaseChar() == 'Z') {
                    cursor.closeSubpath()
                    command = null
                }
            } else {
                val current = command
                if (current == null) {
                    running = false
                } else {
                    val argCount = COMMAND_ARGS[current.uppercaseChar()]
                    if (argCount == null || index + argCount > tokens.size) {
                        running = false
                    } else {
                        val args = (0 until argCount).map { tokens[index + it].toDouble() }
                        index += argCount
                        command = cursor.advance(current, args, points)
                    }
                }
            }
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
