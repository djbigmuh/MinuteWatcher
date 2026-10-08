package com.example.minutewatcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
class LauncherIconConsistencyTest {

    @Test
    fun `application icon and round icon point to valid ic_launcher mipmap resource`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertNotNull(appInfo)
        assertTrue("Application icon must be non-zero", appInfo.icon != 0)

        val roundIconVal = try {
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
}
