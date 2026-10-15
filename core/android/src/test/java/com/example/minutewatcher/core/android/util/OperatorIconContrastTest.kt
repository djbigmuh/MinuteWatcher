package com.example.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class OperatorIconContrastTest {

    @Test
    fun `operator icons have explicit contrast fill and stroke colors`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val iconResIds = SimUtils.operatorIconMap.values.toSet() + R.drawable.ic_unknown

        val resDir = findResDir()
        val drawableDir = File(resDir, "drawable")
        assertTrue("Drawable dir must exist", drawableDir.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()

        iconResIds.forEach { resId ->
            val resName = context.resources.getResourceEntryName(resId)
            val file = File(drawableDir, "$resName.xml")
            if (file.exists()) {
                val doc = builder.parse(file)
                val paths = doc.getElementsByTagName("path")
                assertTrue("Icon $resName must have at least one path", paths.length > 0)

                for (i in 0 until paths.length) {
                    val path = paths.item(i) as org.w3c.dom.Element
                    val fillColor = path.getAttribute("android:fillColor")
                    val strokeColor = path.getAttribute("android:strokeColor")
                    val pathData = path.getAttribute("android:pathData")

                    assertTrue("Path in $resName must have pathData", pathData.isNotEmpty())
                    val hasColor =
                        fillColor.isNotEmpty() || strokeColor.isNotEmpty() || path.getElementsByTagName("gradient").length > 0 || doc.getElementsByTagName(
                            "gradient"
                        ).length > 0
                    assertTrue("Path in $resName must have explicit fill or stroke color", hasColor)
                }
            }
        }
    }

    private fun findResDir(): File {
        val candidates = listOf(
            File("core/resources/src/main/res"),
            File("../core/resources/src/main/res"),
            File("../../core/resources/src/main/res"),
            File("C:/MinuteWatcher/core/resources/src/main/res")
        )
        return candidates.firstOrNull { it.exists() } ?: File("core/resources/src/main/res")
    }
}
