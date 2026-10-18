package host.serv.djbigmuh.minutewatcher.core.resources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPreviewAssetTest {
    @Test
    fun `test widget preview assets compliance`() {
        val resDirProp = System.getProperty("minutewatcher.coreResourcesResDir") ?: "src/main/res"
        val resDir = File(resDirProp)

        val widgetPreviewFile = File(resDir, "drawable/widget_preview.xml")
        val widgetPickerPreviewFile = File(resDir, "layout/widget_picker_preview.xml")
        val widgetPreviewBgFile = File(resDir, "drawable/widget_preview_bg.xml")

        assertTrue("widget_preview.xml must exist", widgetPreviewFile.exists())
        assertTrue("widget_picker_preview.xml must exist", widgetPickerPreviewFile.exists())
        assertTrue("widget_preview_bg.xml must exist", widgetPreviewBgFile.exists())

        val previewXml = widgetPreviewFile.readText()
        assertFalse("widget_preview.xml must not contain #BB86FC", previewXml.contains("BB86FC", ignoreCase = true))

        val pickerPreviewXml = widgetPickerPreviewFile.readText()
        assertTrue(
            "widget_picker_preview.xml must contain ic_operator_mts",
            pickerPreviewXml.contains("@drawable/ic_operator_mts"),
        )
        assertTrue(
            "widget_picker_preview.xml must contain operator_sample",
            pickerPreviewXml.contains("@string/operator_sample"),
        )
        assertFalse(
            "widget_picker_preview.xml must not contain @android:color/",
            pickerPreviewXml.contains("@android:color/"),
        )

        val bgXml = widgetPreviewBgFile.readText()
        assertTrue(
            "widget_preview_bg.xml must refer to @color/widget_preview_bg_color",
            bgXml.contains("@color/widget_preview_bg_color"),
        )

        val colorsFile = File(resDir, "values/colors.xml")
        val nightColorsFile = File(resDir, "values-night/colors.xml")
        assertTrue("values/colors.xml must exist", colorsFile.exists())
        assertTrue("values-night/colors.xml must exist", nightColorsFile.exists())

        assertTrue(
            "colors.xml must define widget_preview_bg_color",
            colorsFile.readText().contains("widget_preview_bg_color"),
        )
        assertTrue(
            "values-night/colors.xml must define widget_preview_bg_color",
            nightColorsFile.readText().contains("widget_preview_bg_color"),
        )

        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val drawable =
            androidx.core.content.ContextCompat.getDrawable(
                appContext,
                R.drawable.widget_preview,
            ) as android.graphics.drawable.Drawable

        val width = 360
        val height = 220
        val totalPixels = width * height
        val bgs = intArrayOf(0xFFFFFFFF.toInt(), 0xFF141218.toInt())

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
                "Contrast ratio on bg ${String.format("#%08X", bg)} was $ratioContrast (less than 0.05)",
                ratioContrast >= 0.05f,
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
