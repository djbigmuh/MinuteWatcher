package com.example.minutewatcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@Config(sdk = [35], application = MinuteWatcherTestApp::class)
@RunWith(RobolectricTestRunner::class)
class MainActivityNavigatorTest {
    @Before
    fun setup() {
        initTestLogger()
    }

    @Test
    fun `addWidgetToHomeScreen handles AppWidgetManager`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val fakeStarter =
            object : ActivityStarter {
                override fun startActivity(
                    context: Context,
                    intent: Intent,
                ) {
                    // No-op for test
                }
            }
        val navigator = MainActivityNavigator(fakeStarter)
        navigator.addWidgetToHomeScreen(appContext)
        val appWidgetManager = appContext.getSystemService(android.appwidget.AppWidgetManager::class.java)
        assertNotNull(appWidgetManager)
    }

    @Test
    fun `openAppSettings does not throw when ActivityNotFoundException occurs`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        var launchedIntent: Intent? = null
        val fakeStarter =
            object : ActivityStarter {
                override fun startActivity(
                    context: Context,
                    intent: Intent,
                ) {
                    launchedIntent = intent
                    throw ActivityNotFoundException("Not found")
                }
            }
        val navigator = MainActivityNavigator(fakeStarter)
        navigator.openAppSettings(appContext)

        assertNotNull(launchedIntent)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, launchedIntent?.action)
        assertEquals("package", launchedIntent?.data?.scheme)
        assertEquals(appContext.packageName, launchedIntent?.data?.schemeSpecificPart)
    }

    @Test
    fun `openUsageAccessSettings launches package scoped usage access intent successfully`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        var launchedIntent: Intent? = null
        val fakeStarter =
            object : ActivityStarter {
                override fun startActivity(
                    context: Context,
                    intent: Intent,
                ) {
                    launchedIntent = intent
                }
            }
        val navigator = MainActivityNavigator(fakeStarter)

        navigator.openUsageAccessSettings(appContext)

        assertNotNull(launchedIntent)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedIntent?.action)
        assertEquals("package", launchedIntent?.data?.scheme)
        assertEquals(appContext.packageName, launchedIntent?.data?.schemeSpecificPart)
    }

    @Test
    fun `openUsageAccessSettings falls back to generic list when package scoped intent fails`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val launchedActions = mutableListOf<String?>()
        val fakeStarter =
            object : ActivityStarter {
                override fun startActivity(
                    context: Context,
                    intent: Intent,
                ) {
                    launchedActions.add(intent.action)
                    if (intent.data != null) {
                        throw ActivityNotFoundException("Package intent failed")
                    }
                }
            }
        val navigator = MainActivityNavigator(fakeStarter)

        navigator.openUsageAccessSettings(appContext)

        assertEquals(2, launchedActions.size)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedActions[0])
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedActions[1])
    }

    @Test
    fun `openUsageAccessSettings falls back to app details settings when both usage access launches fail`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val launchedActions = mutableListOf<String?>()
        val fakeStarter =
            object : ActivityStarter {
                override fun startActivity(
                    context: Context,
                    intent: Intent,
                ) {
                    launchedActions.add(intent.action)
                    throw ActivityNotFoundException("All failed")
                }
            }
        val navigator = MainActivityNavigator(fakeStarter)

        navigator.openUsageAccessSettings(appContext)

        assertEquals(3, launchedActions.size)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedActions[0])
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launchedActions[1])
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, launchedActions[2])
    }

    @Test
    fun `openEmailComposer launches sendto intent with mailto uri and handles ActivityNotFoundException`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        var launchedIntent: Intent? = null
        val fakeStarter =
            object : ActivityStarter {
                override fun startActivity(
                    context: Context,
                    intent: Intent,
                ) {
                    launchedIntent = intent
                    throw ActivityNotFoundException("No email app")
                }
            }
        val navigator = MainActivityNavigator(fakeStarter)
        navigator.openEmailComposer(appContext, "dj.bigmuh@gmail.com")

        assertNotNull(launchedIntent)
        assertEquals(Intent.ACTION_SENDTO, launchedIntent?.action)
        assertEquals("mailto", launchedIntent?.data?.scheme)
        assertEquals("dj.bigmuh@gmail.com", launchedIntent?.data?.schemeSpecificPart)
    }

    @Test
    fun `openEmailComposer shows feedback toast when no email client is available`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val fakeStarter =
            object : ActivityStarter {
                override fun startActivity(
                    context: Context,
                    intent: Intent,
                ): Unit = throw ActivityNotFoundException("No email app")
            }
        val navigator = MainActivityNavigator(fakeStarter)
        navigator.openEmailComposer(appContext, "test@example.com")

        assertEquals(
            appContext.getString(R.string.email_app_not_found),
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
