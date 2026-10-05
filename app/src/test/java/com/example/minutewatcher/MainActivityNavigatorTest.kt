package com.example.minutewatcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.util.initTestLogger
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

    @Test
    fun `addWidgetToHomeScreen handles AppWidgetManager`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        MainActivityNavigator.addWidgetToHomeScreen(appContext)
    }

    @Test
    fun `openAppSettings does not throw when resolveActivity is null`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        MainActivityNavigator.openAppSettings(appContext)
    }

    @Test
    fun `openUsageAccessSettings handles unresolved intents gracefully`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        MainActivityNavigator.openUsageAccessSettings(appContext)
    }
}
