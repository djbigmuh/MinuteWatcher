package com.github.djbigmuh.minutewatcher.core.resources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.core.graphics.PathParser
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OperatorIconAssetComplianceTest {
    @Test
    fun `validate operator icons asset compliance items 1 to 16`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resDir = getResDir()
        val drawableDir = File(resDir, "drawable")
        assertTrue("Drawable directory must exist", drawableDir.exists())

        val operatorIconResIds = getOperatorIconResIds(context)
        assertTrue("Operator icon resources must not be empty", operatorIconResIds.isNotEmpty())

        val dbFactory = DocumentBuilderFactory.newInstance()
        dbFactory.isNamespaceAware = true
        val docBuilder = dbFactory.newDocumentBuilder()

        val failures = mutableListOf<String>()

        for (resId in operatorIconResIds) {
            val resName = context.resources.getResourceEntryName(resId)
            val xmlFile = File(drawableDir, "$resName.xml")
            assertTrue("XML file for $resName must exist at ${xmlFile.absolutePath}", xmlFile.exists())

            val doc = docBuilder.parse(xmlFile)
            val resFailures = validateIcon(context, resId, resName, xmlFile, doc)

            for ((_, failureMsg) in resFailures) {
                failures.add(failureMsg)
            }
        }

        if (failures.isNotEmpty()) {
            fail("Operator icon compliance failures:\n" + failures.joinToString("\n"))
        }
    }

    private fun validateIcon(
        context: Context,
        resId: Int,
        resName: String,
        xmlFile: File,
        doc: Document,
    ): List<Pair<Int, String>> {
        val itemFailures = mutableListOf<Pair<Int, String>>()
        val root = doc.documentElement

        validateRootAndDimensions(resName, root, itemFailures)
        validateGroups(resName, doc, itemFailures)
        validatePathsAndBounds(resName, doc, itemFailures)
        validateColors(resName, doc, itemFailures)
        validateFileAndPathSizes(resName, doc, xmlFile, itemFailures)
        validateEvenOdd(resName, doc, itemFailures)
        validateNamespaces(resName, doc, itemFailures)
        validateGradientColors(resName, doc, itemFailures)
        validatePathCoordinatePrecision(resName, doc, itemFailures)

        val contrastError = checkRenderingContrast(context, resId, resName)
        if (contrastError != null) {
            itemFailures.add(13 to "$resName item 13 failed: $contrastError")
        }

        return itemFailures
    }

    private fun validateRootAndDimensions(
        resName: String,
        root: Element,
        failures: MutableList<Pair<Int, String>>,
    ) {
        if (root.tagName != "vector") {
            failures.add(1 to "$resName item 1 failed: root element is <${root.tagName}> instead of <vector>")
        } else {
            val xmlns = root.getAttribute("xmlns:android")
            if (xmlns != "http://schemas.android.com/apk/res/android") {
                failures.add(1 to "$resName item 1 failed: xmlns:android is '$xmlns'")
            }
        }

        val widthAttr = getAndroidAttr(root, "width")
        val heightAttr = getAndroidAttr(root, "height")
        if (widthAttr != "48dp" || heightAttr != "48dp") {
            failures.add(2 to "$resName item 2 failed: width='$widthAttr', height='$heightAttr'")
        }

        val vpWidth = getAndroidAttr(root, "viewportWidth").toFloatOrNull() ?: -1f
        val vpHeight = getAndroidAttr(root, "viewportHeight").toFloatOrNull() ?: -1f
        if (vpWidth != 48.0f || vpHeight != 48.0f) {
            failures.add(3 to "$resName item 3 failed: viewportWidth='$vpWidth', viewportHeight='$vpHeight'")
        }
    }

    private fun validateGroups(
        resName: String,
        doc: Document,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val groups = doc.getElementsByTagName("group")
        checkEmptyGroups(resName, groups, failures)
        checkGroupTransforms(resName, groups, failures)

        val clipPaths = doc.getElementsByTagName("clip-path")
        checkClipPaths(resName, clipPaths, failures)

        val aaptAttrs = doc.getElementsByTagName("aapt:attr")
        checkAaptAttrs(resName, aaptAttrs, failures)
    }

    private fun checkEmptyGroups(
        resName: String,
        groups: NodeList,
        failures: MutableList<Pair<Int, String>>,
    ) {
        for (i in 0 until groups.length) {
            val g = groups.item(i) as Element
            if (!hasElementChildren(g)) {
                failures.add(11 to "$resName item 11 failed: empty <group> tag found")
            }
        }
    }

    private fun checkGroupTransforms(
        resName: String,
        groups: NodeList,
        failures: MutableList<Pair<Int, String>>,
    ) {
        for (i in 0 until groups.length) {
            val g = groups.item(i) as Element
            val tx = getAndroidAttr(g, "translateX")
            val ty = getAndroidAttr(g, "translateY")
            val sx = getAndroidAttr(g, "scaleX")
            val sy = getAndroidAttr(g, "scaleY")

            val hasTx = tx.isNotEmpty() && tx.toFloatOrNull() != 0f
            val hasTy = ty.isNotEmpty() && ty.toFloatOrNull() != 0f
            val hasSx = sx.isNotEmpty() && sx.toFloatOrNull() != 1f
            val hasSy = sy.isNotEmpty() && sy.toFloatOrNull() != 1f

            val hasPosTransform = hasTx || hasTy
            val hasScaleTransform = hasSx || hasSy
            if (hasPosTransform || hasScaleTransform) {
                failures.add(4 to "$resName item 4 failed: group has transformation attributes tx='$tx', ty='$ty'")
                break
            }
        }
    }

    private fun checkClipPaths(
        resName: String,
        clipPaths: NodeList,
        failures: MutableList<Pair<Int, String>>,
    ) {
        for (i in 0 until clipPaths.length) {
            val cp = clipPaths.item(i) as Element
            val pathData = getAndroidAttr(cp, "pathData")
            if (pathData.isBlank() && !hasElementChildren(cp)) {
                failures.add(11 to "$resName item 11 failed: empty <clip-path> tag found")
            }
        }
    }

    private fun checkAaptAttrs(
        resName: String,
        aaptAttrs: NodeList,
        failures: MutableList<Pair<Int, String>>,
    ) {
        for (i in 0 until aaptAttrs.length) {
            val a = aaptAttrs.item(i) as Element
            if (!hasElementChildren(a)) {
                failures.add(11 to "$resName item 11 failed: empty <aapt:attr> tag found")
            }
        }
    }

    private fun validatePathsAndBounds(
        resName: String,
        doc: Document,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val paths = doc.getElementsByTagName("path")
        val combinedPath = Path()
        var hasPath = false
        var outOfBoundsMsg: String? = null

        for (i in 0 until paths.length) {
            val pathElem = paths.item(i) as Element
            val pathData = getAndroidAttr(pathElem, "pathData")
            if (pathData.isBlank()) {
                failures.add(11 to "$resName item 11 failed: path $i has empty pathData")
                continue
            }

            try {
                val p = PathParser.createPathFromPathData(pathData)
                val rect = RectF()
                p.computeBounds(rect, true)
                combinedPath.addPath(p)
                hasPath = true

                val minOut = rect.left < -0.01f || rect.top < -0.01f
                val maxOut = rect.right > 48.01f || rect.bottom > 48.01f
                val isOutOfBounds = minOut || maxOut
                if (isOutOfBounds) {
                    outOfBoundsMsg =
                        "path bounds [${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}] outside [0, 48]"
                }
            } catch (e: Exception) {
                failures.add(5 to "$resName item 5 failed: parse pathData error: ${e.message}")
            }
        }

        if (outOfBoundsMsg != null) {
            failures.add(5 to "$resName item 5 failed: $outOfBoundsMsg")
        }

        if (hasPath) {
            checkCombinedPathBounds(resName, combinedPath, failures)
        } else {
            failures.add(6 to "$resName item 6 failed: no paths found")
            failures.add(7 to "$resName item 7 failed: no paths found")
        }
    }

    private fun checkCombinedPathBounds(
        resName: String,
        combinedPath: Path,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val totalBounds = RectF()
        combinedPath.computeBounds(totalBounds, true)
        val width = totalBounds.width()
        val height = totalBounds.height()
        val widthRatio = width / 48.0f
        val heightRatio = height / 48.0f

        val widthValid = widthRatio in 0.599f..0.921f
        val minHeightRatio = if (widthRatio > heightRatio) 0.249f else 0.399f
        val heightValid = heightRatio in minHeightRatio..0.921f

        if (!widthValid || !heightValid) {
            failures.add(
                6 to
                    "$resName item 6 failed: bounding box ratio width=${widthRatio * 100}%, height=${heightRatio * 100}%",
            )
        }

        val centerX = (totalBounds.left + totalBounds.right) / 2.0f
        val centerY = (totalBounds.top + totalBounds.bottom) / 2.0f
        val dx = Math.abs(centerX - 24.0f)
        val dy = Math.abs(centerY - 24.0f)

        if (dx > 4.01f || dy > 4.01f) {
            failures.add(7 to "$resName item 7 failed: center ($centerX, $centerY) dx=$dx, dy=$dy")
        }
    }

    private fun validateColors(
        resName: String,
        doc: Document,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val hex8Pattern = Regex("^#[0-9A-Fa-f]{8}$")
        val paths = doc.getElementsByTagName("path")

        for (i in 0 until paths.length) {
            val pathElem = paths.item(i) as Element
            val fillColorAttr = getAndroidAttr(pathElem, "fillColor")
            val strokeColorAttr = getAndroidAttr(pathElem, "strokeColor")
            val hasAaptGradient = hasAaptFillGradient(pathElem)

            val colorMethodCount =
                (if (fillColorAttr.isNotEmpty()) 1 else 0) +
                    (if (hasAaptGradient) 1 else 0) +
                    (if (strokeColorAttr.isNotEmpty()) 1 else 0)

            if (colorMethodCount != 1) {
                failures.add(8 to "$resName item 8 failed: path $i has $colorMethodCount color methods")
            } else {
                if (fillColorAttr.isNotEmpty() && !hex8Pattern.matches(fillColorAttr)) {
                    failures.add(8 to "$resName item 8 failed: path $i fillColor '$fillColorAttr' is not 8-hex")
                }
                if (strokeColorAttr.isNotEmpty() && !hex8Pattern.matches(strokeColorAttr)) {
                    failures.add(8 to "$resName item 8 failed: path $i strokeColor '$strokeColorAttr' is not 8-hex")
                }
            }
        }
    }

    private fun hasAaptFillGradient(pathElem: Element): Boolean {
        val children = pathElem.childNodes
        for (j in 0 until children.length) {
            val child = children.item(j)
            if (child is Element && child.localName == "attr" && child.getAttribute("name") == "android:fillColor") {
                if (child.getElementsByTagName("gradient").length > 0) {
                    return true
                }
            }
        }
        return false
    }

    private fun validateFileAndPathSizes(
        resName: String,
        doc: Document,
        xmlFile: File,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val paths = doc.getElementsByTagName("path")
        var totalPathDataBytes = 0
        for (i in 0 until paths.length) {
            val pathElem = paths.item(i) as Element
            val pathData = getAndroidAttr(pathElem, "pathData")
            totalPathDataBytes += pathData.toByteArray(Charsets.UTF_8).size
        }
        if (totalPathDataBytes > 8000) {
            failures.add(9 to "$resName item 9 failed: total pathData size is $totalPathDataBytes bytes")
        }

        val fileSize = xmlFile.length()
        if (fileSize > 24576) {
            failures.add(10 to "$resName item 10 failed: file size is $fileSize bytes")
        }
    }

    private fun validateEvenOdd(
        resName: String,
        doc: Document,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val paths = doc.getElementsByTagName("path")
        for (i in 0 until paths.length) {
            val pathElem = paths.item(i) as Element
            val pathData = getAndroidAttr(pathElem, "pathData")
            if (requiresEvenOdd(pathData)) {
                val fillType = getAndroidAttr(pathElem, "fillType")
                if (fillType != "evenOdd") {
                    failures.add(12 to "$resName item 12 failed: path $i fillType is '$fillType'")
                }
            }
        }
    }

    private fun requiresEvenOdd(pathData: String): Boolean {
        val subpathRegex = Regex("(?i)M[^MZz]+[Zz]")
        val closedSubpaths = subpathRegex.findAll(pathData).map { it.value }.toList()
        if (closedSubpaths.size < 2) return false

        val boundsList =
            closedSubpaths.mapNotNull { sub ->
                try {
                    val p = PathParser.createPathFromPathData(sub)
                    val r = RectF()
                    p.computeBounds(r, true)
                    r
                } catch (_: Exception) {
                    null
                }
            }

        for (i in boundsList.indices) {
            for (j in boundsList.indices) {
                if (i != j && isRectContained(boundsList[i], boundsList[j])) {
                    return true
                }
            }
        }
        return false
    }

    private fun isRectContained(
        r1: RectF,
        r2: RectF,
    ): Boolean {
        val covers = r1.left <= r2.left && r1.top <= r2.top && r1.right >= r2.right && r1.bottom >= r2.bottom
        return covers && (r1.left < r2.left || r1.top < r2.top || r1.right > r2.right || r1.bottom > r2.bottom)
    }

    private fun checkRenderingContrast(
        context: Context,
        resId: Int,
        resName: String,
    ): String? {
        val drawable = context.getDrawable(resId) ?: return "context.getDrawable($resName) returned null"
        val width = 96
        val height = 96
        val totalPixels = width * height
        val backgrounds =
            intArrayOf(
                0xFFFFFFFF.toInt(),
                0xFF808080.toInt(),
                0xFF141218.toInt(),
            )

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        for (bg in backgrounds) {
            canvas.drawColor(bg, PorterDuff.Mode.SRC)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            val bgLuminance = relativeLuminance(bg)
            var highContrastCount = 0

            val pixels = IntArray(totalPixels)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            for (p in pixels) {
                val pLuminance = relativeLuminance(p)
                val maxL = maxOf(pLuminance, bgLuminance)
                val minL = minOf(pLuminance, bgLuminance)
                val contrast = (maxL + 0.05f) / (minL + 0.05f)
                if (contrast >= 3.0f) {
                    highContrastCount++
                }
            }

            val ratio = highContrastCount.toFloat() / totalPixels
            if (ratio < 0.02f) {
                val bgHex = String.format("#%08X", bg)
                return "on background $bgHex contrast ratio >= 3.0 on only $highContrastCount / $totalPixels pixels"
            }
        }
        return null
    }

    private fun relativeLuminance(color: Int): Float {
        fun sRgbToLinear(c: Int): Float {
            val v = c / 255.0f
            return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        }

        val r = sRgbToLinear((color shr 16) and 0xFF)
        val g = sRgbToLinear((color shr 8) and 0xFF)
        val b = sRgbToLinear(color and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun hasElementChildren(element: Element): Boolean {
        val children = element.childNodes
        for (i in 0 until children.length) {
            if (children.item(i).nodeType == Node.ELEMENT_NODE) {
                return true
            }
        }
        return false
    }

    private fun getAndroidAttr(
        element: Element,
        attrName: String,
    ): String {
        val nsVal = element.getAttributeNS(ANDROID_NS, attrName)
        if (nsVal.isNotEmpty()) return nsVal
        return element.getAttribute("android:$attrName")
    }

    private fun getOperatorIconResIds(context: Context): List<Int> {
        val resources = context.resources
        return R.drawable::class.java.fields
            .mapNotNull { field ->
                try {
                    field.getInt(null)
                } catch (_: Exception) {
                    null
                }
            }.filter { resId ->
                val name =
                    try {
                        resources.getResourceEntryName(resId)
                    } catch (_: Exception) {
                        ""
                    }
                name.startsWith("ic_operator_") || name == "ic_unknown"
            }.sortedBy { context.resources.getResourceEntryName(it) }
    }

    private fun getResDir(): File {
        val prop = System.getProperty("minutewatcher.coreResourcesResDir")
        require(!prop.isNullOrEmpty()) { "System property minutewatcher.coreResourcesResDir is not set" }
        val dir = File(prop)
        require(dir.exists()) { "Core resources directory does not exist at $prop" }
        return dir
    }

    private fun validateNamespaces(
        resName: String,
        doc: Document,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val allElements = doc.getElementsByTagName("*")
        for (i in 0 until allElements.length) {
            val elem = allElements.item(i) as Element
            validateElementNamespaces(resName, elem, failures)
        }
    }

    private fun validateElementNamespaces(
        resName: String,
        elem: Element,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val attrs = elem.attributes
        for (j in 0 until attrs.length) {
            val attr = attrs.item(j)
            val attrName = attr.nodeName
            if (attrName.startsWith("xmlns:")) {
                validateNamespaceAttr(resName, attrName, attr.nodeValue, failures)
            } else if (attrName == "xmlns") {
                failures.add(
                    14 to
                        "$resName item 14 failed: default namespace prefix 'xmlns' with URI '${attr.nodeValue}' is not allowed",
                )
            }
        }
    }

    private fun validateNamespaceAttr(
        resName: String,
        attrName: String,
        uri: String,
        failures: MutableList<Pair<Int, String>>,
    ) {
        when (val prefix = attrName.substring(6)) {
            "android" -> {
                if (uri != ANDROID_NS) {
                    failures.add(14 to "$resName item 14 failed: namespace prefix '$prefix' has invalid URI '$uri'")
                }
            }
            "aapt" -> {
                if (uri != "http://schemas.android.com/aapt") {
                    failures.add(14 to "$resName item 14 failed: namespace prefix '$prefix' has invalid URI '$uri'")
                }
            }
            else -> {
                failures.add(14 to "$resName item 14 failed: unauthorized namespace prefix '$prefix' with URI '$uri'")
            }
        }
    }

    private fun validateGradientColors(
        resName: String,
        doc: Document,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val hex8Pattern = Regex("^#[0-9A-Fa-f]{8}$")
        val gradients = doc.getElementsByTagName("gradient")
        for (i in 0 until gradients.length) {
            val gradient = gradients.item(i) as Element
            val items = gradient.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                val colorAttr = getAndroidAttr(item, "color")
                if (!hex8Pattern.matches(colorAttr)) {
                    failures.add(
                        15 to "$resName item 15 failed: gradient item color '$colorAttr' is not 8-hex",
                    )
                }
            }
        }
    }

    private fun validatePathCoordinatePrecision(
        resName: String,
        doc: Document,
        failures: MutableList<Pair<Int, String>>,
    ) {
        val precisionPattern = Regex("""\d+\.\d{3,}""")
        val paths = doc.getElementsByTagName("path")
        for (i in 0 until paths.length) {
            val pathElem = paths.item(i) as Element
            val pathData = getAndroidAttr(pathElem, "pathData")
            for (match in precisionPattern.findAll(pathData)) {
                val literal = match.value
                failures.add(
                    16 to
                        "$resName item 16 failed: path $i pathData coordinate '$literal' has more than 2 decimal places",
                )
            }
        }
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
