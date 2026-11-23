package com.github.djbigmuh.minutewatcher.core.resources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutIconConsistencyTest {
    @Test
    fun `test shortcut icon asset and rendering compliance`() {
        val coreResDirProp =
            System.getProperty("minutewatcher.coreResourcesResDir")
                ?: error("System property minutewatcher.coreResourcesResDir is not set")
        val coreResDir = File(coreResDirProp)
        val shortcutFile = File(coreResDir, "drawable/ic_shortcut_usage.xml")

        assertTrue("ic_shortcut_usage.xml must exist", shortcutFile.exists())

        val dbFactory = DocumentBuilderFactory.newInstance()
        dbFactory.isNamespaceAware = true
        val shortcutDoc = dbFactory.newDocumentBuilder().parse(shortcutFile)
        val shortcutRoot = shortcutDoc.documentElement

        assertEquals("48", shortcutRoot.getAttribute("android:width").removeSuffix("dp"))
        assertEquals("48", shortcutRoot.getAttribute("android:height").removeSuffix("dp"))
        assertEquals(48.0f, shortcutRoot.getAttribute("android:viewportWidth").toFloat(), 0.01f)
        assertEquals(48.0f, shortcutRoot.getAttribute("android:viewportHeight").toFloat(), 0.01f)

        val paths = shortcutDoc.getElementsByTagName("path")
        assertTrue("Shortcut icon must contain paths", paths.length > 0)

        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val drawable =
            androidx.core.content.ContextCompat.getDrawable(
                appContext,
                R.drawable.ic_shortcut_usage,
            ) as android.graphics.drawable.Drawable

        val width = 96
        val height = 96
        val totalPixels = width * height
        val bgs = intArrayOf(0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF141218.toInt())

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        for (bg in bgs) {
            canvas.drawColor(bg, PorterDuff.Mode.SRC)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            val bgLum = relativeLuminance(bg)
            var highContrastCount = 0
            val pixels = IntArray(totalPixels)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            for (p in pixels) {
                val pLum = relativeLuminance(p)
                val maxL = maxOf(pLum, bgLum)
                val minL = minOf(pLum, bgLum)
                val contrast = (maxL + 0.05f) / (minL + 0.05f)
                if (contrast >= 3.0f) {
                    highContrastCount++
                }
            }

            val ratioContrast = highContrastCount.toFloat() / totalPixels
            assertTrue(
                "Contrast ratio on bg ${String.format("#%08X", bg)} was $ratioContrast (less than 0.02)",
                ratioContrast >= 0.02f,
            )
        }
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
}
