package com.example.minutewatcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = MinuteWatcherTestApp::class)
@RunWith(RobolectricTestRunner::class)
class MainActivityNavigatorTest {
    @Before
    fun setup() {
        initTestLogger()
    }

    @After
    fun tearDown() {
        MainActivityNavigator.startActivityOverride = null
    }

    @Test
    fun `addWidgetToHomeScreen handles AppWidgetManager`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        MainActivityNavigator.addWidgetToHomeScreen(appContext)
    }

    @Test
    fun `openAppSettings does not throw when ActivityNotFoundException occurs`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        MainActivityNavigator.startActivityOverride = { _, _ ->
            throw ActivityNotFoundException("Not found")
        }
        MainActivityNavigator.openAppSettings(appContext)
    }

    @Test
    fun `openUsageAccessSettings launches package scoped usage access intent successfully`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        var launchedIntent: Intent? = null
        MainActivityNavigator.startActivityOverride = { _, intent ->
            launchedIntent = intent
        }

        MainActivityNavigator.openUsageAccessSettings(appContext)

        assertNotNull(launchedIntent)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedIntent?.action)
        assertEquals("package", launchedIntent?.data?.scheme)
        assertEquals(appContext.packageName, launchedIntent?.data?.schemeSpecificPart)
    }

    @Test
    fun `openUsageAccessSettings falls back to generic list when package scoped intent fails`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val launchedActions = mutableListOf<String?>()
        MainActivityNavigator.startActivityOverride = { _, intent ->
            launchedActions.add(intent.action)
            if (intent.data != null) {
                throw ActivityNotFoundException("Package intent failed")
            }
        }

        MainActivityNavigator.openUsageAccessSettings(appContext)

        assertEquals(2, launchedActions.size)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedActions[0])
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedActions[1])
    }

    @Test
    fun `openUsageAccessSettings falls back to app details settings when both usage access launches fail`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val launchedActions = mutableListOf<String?>()
        MainActivityNavigator.startActivityOverride = { _, intent ->
            launchedActions.add(intent.action)
            throw ActivityNotFoundException("All failed")
        }

        MainActivityNavigator.openUsageAccessSettings(appContext)

        assertEquals(3, launchedActions.size)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedActions[0])
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedActions[1])
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, launchedActions[2])
    }
}
