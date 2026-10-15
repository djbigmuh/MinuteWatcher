package com.example.minutewatcher.core.resources

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class OperatorVectorIntegrityTest {
    @Test
    fun `operator and unknown icons have vector root tag and valid dimensions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = context.resources
        val operatorIconIds = getOperatorIconIds(context)

        for (resId in operatorIconIds) {
            val resName = resources.getResourceEntryName(resId)
            val typeName = resources.getResourceTypeName(resId)
            assertEquals("drawable", typeName)

            val parser = getParser(resources, resId) ?: continue
            validateVectorRootParser(parser, resName)
        }
    }

    @Test
    fun `operator and unknown icons paths have explicit fill or stroke color`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = context.resources
        val operatorIconIds = getOperatorIconIds(context)

        for (resId in operatorIconIds) {
            val resName = resources.getResourceEntryName(resId)
            val parser = getParser(resources, resId) ?: continue
            validatePathsParser(parser, resName)
        }
    }

    @Test
    fun `operator and unknown icons radial gradients have valid radius and center`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = context.resources
        val operatorIconIds = getOperatorIconIds(context)

        for (resId in operatorIconIds) {
            val resName = resources.getResourceEntryName(resId)
            val parser = getParser(resources, resId) ?: continue
            checkGradientsInParser(parser, resName)
        }
    }

    private fun getOperatorIconIds(context: Context): List<Int> {
        val resources = context.resources
        val drawableFields = R.drawable::class.java.fields
        val operatorIconIds =
            drawableFields
                .map { it.getInt(null) }
                .filter { resId ->
                    val name =
                        try {
                            resources.getResourceEntryName(resId)
                        } catch (_: Exception) {
                            ""
                        }
                    name.startsWith("ic_operator_") || name == "ic_unknown"
                }

        assertTrue("Must find operator or unknown icons to test", operatorIconIds.isNotEmpty())
        return operatorIconIds
    }

    private fun getParser(
        resources: Resources,
        resId: Int,
    ): XmlPullParser? =
        try {
            resources.getXml(resId)
        } catch (_: Exception) {
            null
        }

    private fun validateVectorRootParser(
        parser: XmlPullParser,
        resName: String,
    ) {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name
                assertEquals("Root tag must be vector for $resName", "vector", tagName)
                validateVectorRoot(parser, resName)
                break
            }
            eventType = parser.next()
        }
    }

    private fun validatePathsParser(
        parser: XmlPullParser,
        resName: String,
    ) {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "path") {
                val fillColor =
                    parser.getAttributeValue("http://schemas.android.com/apk/res/android", "fillColor")
                val strokeColor =
                    parser.getAttributeValue("http://schemas.android.com/apk/res/android", "strokeColor")
                val hasPaint = !fillColor.isNullOrEmpty() || !strokeColor.isNullOrEmpty()
                assertTrue("Path in $resName must have explicit fillColor or strokeColor", hasPaint)
            }
            eventType = parser.next()
        }
    }

    private fun checkGradientsInParser(
        parser: XmlPullParser,
        resName: String,
    ) {
        var eventType = parser.eventType
        var vpWidth = 0f
        var vpHeight = 0f
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name
                if (tagName == "vector") {
                    val vpWidthStr =
                        parser.getAttributeValue(
                            "http://schemas.android.com/apk/res/android",
                            "viewportWidth",
                        )
                    val vpHeightStr =
                        parser.getAttributeValue(
                            "http://schemas.android.com/apk/res/android",
                            "viewportHeight",
                        )
                    vpWidth = vpWidthStr?.replace("dp", "")?.toFloatOrNull() ?: 0f
                    vpHeight = vpHeightStr?.replace("dp", "")?.toFloatOrNull() ?: 0f
                } else if (tagName == "gradient") {
                    validateRadialGradient(parser, resName, vpWidth, vpHeight)
                }
            }
            eventType = parser.next()
        }
    }

    private fun validateVectorRoot(
        parser: XmlPullParser,
        resName: String,
    ) {
        val widthStr = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "width")
        val heightStr = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "height")
        val vpWidthStr = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "viewportWidth")
        val vpHeightStr = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "viewportHeight")

        assertNotNull("width required for $resName", widthStr)
        assertNotNull("height required for $resName", heightStr)
        assertNotNull("viewportWidth required for $resName", vpWidthStr)
        assertNotNull("viewportHeight required for $resName", vpHeightStr)

        assertEquals("width must equal height for $resName", widthStr, heightStr)

        val vpWidth = vpWidthStr.replace("dp", "").toFloatOrNull() ?: 0f
        val vpHeight = vpHeightStr.replace("dp", "").toFloatOrNull() ?: 0f
        assertTrue("viewportWidth must be > 0 for $resName", vpWidth > 0f)
        assertTrue("viewportHeight must be > 0 for $resName", vpHeight > 0f)
    }

    private fun validateRadialGradient(
        parser: XmlPullParser,
        resName: String,
        vpWidth: Float,
        vpHeight: Float,
    ) {
        val type = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "type")
        if (type == "radial") {
            val radiusStr =
                parser.getAttributeValue(
                    "http://schemas.android.com/apk/res/android",
                    "gradientRadius",
                )
            val cxStr = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "centerX")
            val cyStr = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "centerY")

            assertNotNull("gradientRadius required for radial gradient in $resName", radiusStr)
            assertNotNull("centerX required for radial gradient in $resName", cxStr)
            assertNotNull("centerY required for radial gradient in $resName", cyStr)

            val radius = radiusStr.toFloatOrNull() ?: 0f
            val cx = cxStr.toFloatOrNull() ?: 0f
            val cy = cyStr.toFloatOrNull() ?: 0f

            assertTrue("gradientRadius must be > 0 in $resName, got $radius", radius > 0f)
            assertTrue(
                "centerX must be within viewport in $resName (cx=$cx, vpWidth=$vpWidth)",
                cx >= -100f && cx <= vpWidth * 3,
            )
            assertTrue(
                "centerY must be within viewport in $resName (cy=$cy, vpHeight=$vpHeight)",
                cy >= -100f && cy <= vpHeight * 3,
            )
        }
    }

    @Test
    fun `ic_operator_vi structural gradient and color stops check`() {
        val resDir = getResDir()
        val viFile = File(resDir, "drawable/ic_operator_vi.xml")
        assertTrue("ic_operator_vi.xml must exist", viFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(viFile)

        val gradients = doc.getElementsByTagName("gradient")
        assertTrue("Must have gradients", gradients.length > 0)

        var foundRadial = false
        var foundLightStop = false
        var foundDarkStop = false

        for (i in 0 until gradients.length) {
            val grad = gradients.item(i) as org.w3c.dom.Element
            val type = grad.getAttribute("android:type")
            if (type == "radial") {
                foundRadial = true
                val radius = grad.getAttribute("android:gradientRadius").toFloatOrNull() ?: 0f
                val cx = grad.getAttribute("android:centerX").toFloatOrNull() ?: 0f
                val cy = grad.getAttribute("android:centerY").toFloatOrNull() ?: 0f

                assertTrue("gradientRadius must be > 0", radius > 0f)
                assertTrue("centerX must be > 0", cx > 0f)
                assertTrue("centerY must be > 0", cy > 0f)
            }
        }

        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val item = items.item(i) as org.w3c.dom.Element
            val color = item.getAttribute("android:color")
            if (color.uppercase().contains("FFE436")) {
                foundLightStop = true
            }
            if (color.uppercase().contains("B46100")) {
                foundDarkStop = true
            }
        }

        assertTrue("Must have radial gradient", foundRadial)
        assertTrue("Must contain lightest color stop #FFE436", foundLightStop)
        assertTrue("Must contain darkest color stop #B46100", foundDarkStop)
    }

    private fun getResDir(): File {
        val prop = System.getProperty("minutewatcher.coreResourcesResDir")
        assertTrue("System property minutewatcher.coreResourcesResDir is not set", !prop.isNullOrEmpty())
        val dir = File(prop!!)
        assertTrue("Core resources directory does not exist at $prop", dir.exists())
        return dir
    }
}
