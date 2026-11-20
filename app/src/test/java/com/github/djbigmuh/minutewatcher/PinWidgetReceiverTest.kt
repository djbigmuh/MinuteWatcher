package com.github.djbigmuh.minutewatcher

import android.app.LocaleManager
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.resources.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PinWidgetReceiverTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var mockPendingResult: BroadcastReceiver.PendingResult
    private var initialLocales: LocaleListCompat = LocaleListCompat.getEmptyLocaleList()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
        initialLocales = AppCompatDelegate.getApplicationLocales()
    }

    @After
    fun tearDown() {
        setAppLocale(initialLocales)
    }

    private fun setAppLocale(locales: LocaleListCompat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (localeManager != null) {
                val targetList =
                    if (locales.isEmpty) {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        val first = locales.get(0)
                        if (first != null) LocaleList(first) else LocaleList.getEmptyLocaleList()
                    }
                localeManager.applicationLocales = targetList
            }
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    @Test
    fun `onReceive with invalid appWidgetId exits early without acquiring pendingResult or updating widget`() =
        runTest(timeout = 5.seconds) {
            var acquireCount = 0
            var updateCount = 0

            val receiver =
                object : PinWidgetReceiver() {
                    override fun acquirePendingResult(): PendingResult {
                        acquireCount++
                        return mockPendingResult
                    }

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun updateWidget(
                        appContext: Context,
                        appWidgetId: Int,
                    ) {
                        updateCount++
                    }
                }

            val intent = Intent() // No EXTRA_APPWIDGET_ID extra
            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            assertEquals(0, acquireCount)
            assertEquals(0, updateCount)
            verify(mockPendingResult, never()).finish()
        }

    @Test
    fun `onReceive with valid appWidgetId calls updateWidget and finishes pendingResult once`() =
        runTest(timeout = 5.seconds) {
            var acquireCount = 0
            var updateCount = 0
            var capturedWidgetId = -1

            val receiver =
                object : PinWidgetReceiver() {
                    override fun acquirePendingResult(): PendingResult {
                        acquireCount++
                        return mockPendingResult
                    }

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun updateWidget(
                        appContext: Context,
                        appWidgetId: Int,
                    ) {
                        updateCount++
                        capturedWidgetId = appWidgetId
                    }
                }

            val intent =
                Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 42)
                }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            assertEquals(1, acquireCount)
            assertEquals(1, updateCount)
            assertEquals(42, capturedWidgetId)
            verify(mockPendingResult, Mockito.times(1)).finish()
        }

    @Test
    fun `onReceive finishes pendingResult once when updateWidget throws exception`() =
        runTest(timeout = 5.seconds) {
            var acquireCount = 0
            var updateCount = 0

            val receiver =
                object : PinWidgetReceiver() {
                    override fun acquirePendingResult(): PendingResult {
                        acquireCount++
                        return mockPendingResult
                    }

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun updateWidget(
                        appContext: Context,
                        appWidgetId: Int,
                    ) {
                        updateCount++
                        throw IllegalStateException("Simulated widget update failure")
                    }
                }

            val intent =
                Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 100)
                }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            assertEquals(1, acquireCount)
            assertEquals(1, updateCount)
            verify(mockPendingResult, Mockito.times(1)).finish()
        }

    @Test
    fun `resolveSuccessMessage returns default system locale string when app locales empty`() {
        setAppLocale(LocaleListCompat.getEmptyLocaleList())
        val receiver =
            object : PinWidgetReceiver() {
                fun callResolve(c: Context): String = resolveSuccessMessage(c)
            }
        val message = receiver.callResolve(context)
        val expected = context.getString(R.string.widget_added_success)
        assertEquals(expected, message)
    }

    @Test
    fun `resolveSuccessMessage returns localized translation when app locale is set`() {
        val germanLocale = Locale.forLanguageTag("de")
        setAppLocale(LocaleListCompat.create(germanLocale))
        try {
            val receiver =
                object : PinWidgetReceiver() {
                    fun callResolve(c: Context): String = resolveSuccessMessage(c)
                }
            val message = receiver.callResolve(context)
            val config = Configuration(context.resources.configuration)
            config.setLocale(germanLocale)
            val localizedContext = context.createConfigurationContext(config)
            val expected = localizedContext.getString(R.string.widget_added_success)
            assertEquals(expected, message)
        } finally {
            setAppLocale(LocaleListCompat.getEmptyLocaleList())
        }
    }

    @Test
    fun `onReceive with valid appWidgetId shows success toast with resolved message and updates widget`() =
        runTest(timeout = 5.seconds) {
            var acquireCount = 0
            var updateCount = 0
            var shownMessage: String? = null

            val receiver =
                object : PinWidgetReceiver() {
                    override fun acquirePendingResult(): PendingResult {
                        acquireCount++
                        return mockPendingResult
                    }

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override fun showSuccessToast(
                        context: Context,
                        message: String,
                    ) {
                        shownMessage = message
                    }

                    override suspend fun updateWidget(
                        appContext: Context,
                        appWidgetId: Int,
                    ) {
                        updateCount++
                    }
                }

            val intent =
                Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 42)
                }

            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            assertEquals(1, acquireCount)
            assertEquals(1, updateCount)
            assertEquals(context.getString(R.string.widget_added_success), shownMessage)
            verify(mockPendingResult, Mockito.times(1)).finish()
        }
}
