package com.github.djbigmuh.minutewatcher

import android.content.Context
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = MinuteWatcherTestApp::class)
class LauncherIconConsistencyTest {
    @Test
    fun `application icon and round icon point to valid ic_launcher mipmap resource`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertNotNull(appInfo)
        assertTrue("Application icon must be non-zero", appInfo.icon != 0)

        val roundIconVal =
            try {
                val field = appInfo.javaClass.getField("roundIcon")
                field.getInt(appInfo)
            } catch (_: Exception) {
                context.resources.getIdentifier("ic_launcher", "mipmap", context.packageName)
            }
        assertTrue("Application round icon must be non-zero", roundIconVal != 0)

        val iconResName = context.resources.getResourceEntryName(appInfo.icon)
        val iconResType = context.resources.getResourceTypeName(appInfo.icon)
        assertEquals("ic_launcher", iconResName)
        assertEquals("mipmap", iconResType)

        val roundIconResName = context.resources.getResourceEntryName(roundIconVal)
        val roundIconResType = context.resources.getResourceTypeName(roundIconVal)
        assertEquals("ic_launcher", roundIconResName)
        assertEquals("mipmap", roundIconResType)
    }

    @Test
    fun `application icon resolves to AdaptiveIconDrawable and renders successfully`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        val drawable = context.getDrawable(appInfo.icon)
        assertNotNull(drawable)
        assertTrue(
            "Application icon must be AdaptiveIconDrawable",
            drawable is AdaptiveIconDrawable,
        )

        val nonNullDrawable = checkNotNull(drawable)
        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        nonNullDrawable.setBounds(0, 0, 108, 108)
        nonNullDrawable.draw(canvas)

        assertTrue(nonNullDrawable.intrinsicWidth > 0)
        assertTrue(nonNullDrawable.intrinsicHeight > 0)
    }

    @Test
    fun `ic_launcher_round variant is absent in app resources`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val roundId = context.resources.getIdentifier("ic_launcher_round", "mipmap", context.packageName)
        assertEquals("ic_launcher_round should not exist in application resources", 0, roundId)
    }

    @Test
    fun `each icon layer has opaque pixels in central safe zone`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val layerIds =
            listOf(
                R.drawable.ic_launcher_background,
                R.drawable.ic_launcher_foreground,
                R.drawable.ic_launcher_monochrome,
            )

        layerIds.forEach { resId ->
            assertLayerHasOpaquePixels(context, resId)
        }
    }

    private fun assertLayerHasOpaquePixels(
        context: Context,
        resId: Int,
    ) {
        val layerName = context.resources.getResourceEntryName(resId)
        val drawable = AppCompatResources.getDrawable(context, resId)
        assertNotNull("Layer $layerName must not be null", drawable)

        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable!!.setBounds(0, 0, 108, 108)
        drawable.draw(canvas)

        var hasOpaquePixelInSafeZone = false
        for (x in 21..87) {
            for (y in 21..87) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 0) {
                    hasOpaquePixelInSafeZone = true
                    break
                }
            }
            if (hasOpaquePixelInSafeZone) break
        }

        assertTrue(
            "Layer $layerName must have at least one opaque pixel in central safe zone (21..87)",
            hasOpaquePixelInSafeZone,
        )
    }

    @Test
    fun `composite adaptive icon has opaque pixels in central safe zone`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        val drawable = context.getDrawable(appInfo.icon)
        assertTrue(drawable is AdaptiveIconDrawable)

        val adaptiveIcon = drawable as AdaptiveIconDrawable
        assertNotNull(adaptiveIcon.monochrome)

        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        adaptiveIcon.setBounds(0, 0, 108, 108)
        adaptiveIcon.draw(canvas)

        var hasOpaquePixelInSafeZone = false
        for (x in 21..87) {
            for (y in 21..87) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) > 0) {
                    hasOpaquePixelInSafeZone = true
                    break
                }
            }
            if (hasOpaquePixelInSafeZone) break
        }

        assertTrue(
            "Composite adaptive icon must have at least one opaque pixel in central safe zone",
            hasOpaquePixelInSafeZone,
        )
    }

    @Test
    fun `icon layers do not contain theme attribute references`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val layerIds =
            listOf(
                R.drawable.ic_launcher_background,
                R.drawable.ic_launcher_foreground,
                R.drawable.ic_launcher_monochrome,
            )

        layerIds.forEach { resId ->
            assertNoThemeAttributeReferences(context, resId)
        }
    }

    private fun assertNoThemeAttributeReferences(
        context: Context,
        resId: Int,
    ) {
        val resName = context.resources.getResourceEntryName(resId)
        val parser = context.resources.getXml(resId)
        var eventType = parser.eventType
        while (eventType != XmlResourceParser.END_DOCUMENT) {
            if (eventType == XmlResourceParser.START_TAG) {
                checkAttributes(resName, parser)
            }
            eventType = parser.next()
        }
    }

    private fun checkAttributes(
        resName: String,
        parser: XmlResourceParser,
    ) {
        val tagName = parser.name
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i)
            val attrValue = parser.getAttributeValue(i)
            if (attrValue != null && attrValue.startsWith("?")) {
                org.junit.Assert.fail(
                    "Resource $resName element $tagName attribute $attrName " +
                        "has theme attribute reference '$attrValue'. " +
                        "Theme attributes are prohibited in launcher icon layers.",
                )
            }
        }
    }

    @Test
    fun `foreground and monochrome layers do not exceed safe zone radius 34dp`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val layerIds = listOf(R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_monochrome)

        layerIds.forEach { resId ->
            val layerName = context.resources.getResourceEntryName(resId)
            val drawable = AppCompatResources.getDrawable(context, resId)
            assertNotNull(drawable)

            val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable!!.setBounds(0, 0, 108, 108)
            drawable.draw(canvas)

            val centerX = 54.0
            val centerY = 54.0
            val maxAllowedRadius = 35.0 // 34dp + 1dp tolerance

            var checkedPixels = 0
            for (x in 0 until 108) {
                for (y in 0 until 108) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.alpha(pixel) > 0) {
                        checkedPixels++
                        val dx = x - centerX
                        val dy = y - centerY
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        assertTrue(
                            "Layer $layerName has pixel at ($x, $y) with distance $dist exceeding safe zone radius $maxAllowedRadius",
                            dist <= maxAllowedRadius,
                        )
                    }
                }
            }
            assertTrue("Layer $layerName must have checked pixels", checkedPixels > 0)
        }
    }

    @Test
    fun `background layer has uniform color across entire canvas`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_launcher_background)
        assertNotNull(drawable)

        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable!!.setBounds(0, 0, 108, 108)
        drawable.draw(canvas)

        val firstPixel = bitmap.getPixel(54, 54)
        assertTrue("Background pixels must be opaque", android.graphics.Color.alpha(firstPixel) > 0)

        for (x in 10 until 98 step 10) {
            for (y in 10 until 98 step 10) {
                assertEquals("Background must have uniform color", firstPixel, bitmap.getPixel(x, y))
            }
        }
    }

    @Test
    fun `monochrome layer has opaque pixels inside radius 33dp`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drawable = AppCompatResources.getDrawable(context, R.drawable.ic_launcher_monochrome)
        assertNotNull(drawable)

        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable!!.setBounds(0, 0, 108, 108)
        drawable.draw(canvas)

        val centerX = 54.0
        val centerY = 54.0
        var foundOpaqueInside = false

        for (x in (54 - 30)..(54 + 30)) {
            for (y in (54 - 30)..(54 + 30)) {
                val dx = x - centerX
                val dy = y - centerY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist <= 33.0) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.alpha(pixel) > 50) {
                        foundOpaqueInside = true
                        break
                    }
                }
            }
            if (foundOpaqueInside) break
        }
        assertTrue("Monochrome layer must contain opaque pixels inside radius 33dp", foundOpaqueInside)
    }
}
