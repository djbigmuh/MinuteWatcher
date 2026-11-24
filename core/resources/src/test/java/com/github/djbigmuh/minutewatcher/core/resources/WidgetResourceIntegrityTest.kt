package com.github.djbigmuh.minutewatcher.core.resources

import com.github.djbigmuh.minutewatcher.core.resources.testing.ProjectPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WidgetResourceIntegrityTest {
    @Test
    fun `preview layout differs from initial layout and has no progress bar`() {
        val resDir = findResDir()
        val widgetInfoFile = File(resDir, "xml/minutes_widget_info.xml")
        assertTrue("minutes_widget_info.xml must exist", widgetInfoFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(widgetInfoFile)
        val root = doc.documentElement

        val previewLayout = root.getAttribute("android:previewLayout")
        val initialLayout = root.getAttribute("android:initialLayout")
        val previewImage = root.getAttribute("android:previewImage")

        assertNotNull("previewLayout must be present", previewLayout)
        assertNotNull("initialLayout must be present", initialLayout)
        assertNotNull("previewImage must be present", previewImage)

        assertNotEquals(
            "android:previewLayout must not equal android:initialLayout",
            initialLayout,
            previewLayout,
        )
        assertEquals("@layout/widget_picker_preview", previewLayout)
        assertEquals("@layout/widget_layout", initialLayout)

        val previewLayoutFile = File(resDir, "layout/widget_picker_preview.xml")
        assertTrue("widget_picker_preview.xml must exist", previewLayoutFile.exists())

        val previewContent = previewLayoutFile.readText()
        assertTrue("preview layout must not contain ProgressBar", !previewContent.contains("ProgressBar"))
    }

    private fun findResDir(): File = ProjectPaths.resDir
}
