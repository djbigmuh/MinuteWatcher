package com.example.minutewatcher.core.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Consistency guard between operator icon XML content and the monochrome classification
 * used to pick a theme-aware tint at render time.
 *
 * Classification rule: an icon is monochrome when every visible solid color found in
 * path fill/stroke attributes and gradient items (literal #RRGGBB or #AARRGGBB with
 * alpha greater than 0.1) is near-black (relative luminance <= 0.08), or every such
 * color is near-white (relative luminance >= 0.92). Relative luminance is computed as
 * 0.2126 * R + 0.7152 * G + 0.0722 * B over sRGB channels normalized to [0, 1]
 * without gamma expansion.
 */
class OperatorIconMonochromeTest {
    @Test
    fun `monochrome classification matches icon xml content`() {
        val drawableDir = File(resDir(), "drawable")
        assertTrue("Drawable dir must exist at $drawableDir", drawableDir.exists())

        val files =
            drawableDir
                .listFiles { file ->
                    file.extension == "xml" &&
                        (file.name.startsWith("ic_operator_") || file.name == "ic_unknown.xml")
                }.orEmpty()
                .sortedBy { it.name }
        assertTrue("Operator icon files must be found in $drawableDir", files.isNotEmpty())

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val drawableFields = Class.forName("com.example.minutewatcher.core.resources.R\$drawable")

        val classified = mutableSetOf<Int>()
        for (file in files) {
            val monochrome = isMonochrome(builder.parse(file).documentElement)
            if (file.name == "ic_unknown.xml") {
                assertFalse("ic_unknown must not be classified as monochrome", monochrome)
                continue
            }
            if (monochrome) {
                classified.add(drawableFields.getField(file.nameWithoutExtension).getInt(null))
            }
        }

        assertEquals(
            "SimUtils.monochromeOperatorIcons diverged from icon XML content",
            classified,
            SimUtils.monochromeOperatorIcons,
        )
    }

    private fun resDir(): String {
        val prop = System.getProperty("minutewatcher.coreResourcesResDir")
        assertTrue("System property minutewatcher.coreResourcesResDir is not set", !prop.isNullOrEmpty())
        return prop!!
    }

    private fun isMonochrome(root: Element): Boolean {
        val luminances = visibleLuminances(root)
        if (luminances.isEmpty()) return false
        return luminances.all { it <= DARK_LUMINANCE } || luminances.all { it >= LIGHT_LUMINANCE }
    }

    private fun visibleLuminances(root: Element): List<Double> =
        collectColorLiterals(root)
            .mapNotNull { parseColor(it) }
            .filter { it.second > ALPHA_THRESHOLD }
            .map { luminance(it.first) }

    private fun collectColorLiterals(root: Element): List<String> {
        val colors = mutableListOf<String>()
        appendAttrColors(root, "path", listOf("android:fillColor", "android:strokeColor"), colors)
        appendAttrColors(root, "item", listOf("android:color"), colors)
        return colors
    }

    private fun appendAttrColors(
        root: Element,
        tagName: String,
        attrNames: List<String>,
        out: MutableList<String>,
    ) {
        val nodes = root.getElementsByTagName(tagName)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node !is Element) continue
            for (attr in attrNames) {
                val value = node.getAttribute(attr)
                if (value.startsWith("#")) {
                    out.add(value)
                }
            }
        }
    }

    /**
     * Parses a literal color into (rgb channels normalized to [0, 1], alpha normalized to [0, 1]).
     * Supported formats: #RGB is not used in project icons and is rejected; #RRGGBB and #AARRGGBB.
     */
    private fun parseColor(value: String): Pair<Triple<Double, Double, Double>, Double>? {
        val hex = value.removePrefix("#")
        val (alphaHex, rgbHex) =
            when (hex.length) {
                HEX_LENGTH_RGB -> "ff" to hex
                HEX_LENGTH_ARGB -> hex.substring(0, 2) to hex.substring(2)
                else -> return null
            }
        val alpha = Integer.parseInt(alphaHex, HEX_RADIX) / 255.0
        val r = Integer.parseInt(rgbHex.substring(0, 2), HEX_RADIX) / 255.0
        val g = Integer.parseInt(rgbHex.substring(2, 4), HEX_RADIX) / 255.0
        val b = Integer.parseInt(rgbHex.substring(4, 6), HEX_RADIX) / 255.0
        return Triple(r, g, b) to alpha
    }

    private fun luminance(rgb: Triple<Double, Double, Double>): Double =
        0.2126 * rgb.first + 0.7152 * rgb.second + 0.0722 * rgb.third

    private companion object {
        const val DARK_LUMINANCE = 0.08
        const val LIGHT_LUMINANCE = 0.92
        const val ALPHA_THRESHOLD = 0.1
        const val HEX_LENGTH_RGB = 6
        const val HEX_LENGTH_ARGB = 8
        const val HEX_RADIX = 16
    }
}
