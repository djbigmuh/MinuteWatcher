package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.DataChangeNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LocaleChangedReceiverTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var mockPendingResult: BroadcastReceiver.PendingResult
    private lateinit var mockNotifier: DataChangeNotifier

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockPendingResult = mock(BroadcastReceiver.PendingResult::class.java)
        mockNotifier = mock(DataChangeNotifier::class.java)
    }

    @Test
    fun `onReceive with non locale changed action exits early without update`() =
        runTest {
            var acquireCount = 0
            val receiver =
                object : LocaleChangedReceiver() {
                    override fun acquirePendingResult(): PendingResult {
                        acquireCount++
                        return mockPendingResult
                    }

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun provideDataChangeNotifier(appContext: Context): DataChangeNotifier =
                        mockNotifier
                }

            val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            assertEquals(0, acquireCount)
            verify(mockNotifier, never()).notifyDataChanged(anyBoolean(), anyBoolean())
            verify(mockPendingResult, never()).finish()
        }

    @Test
    fun `onReceive with action locale changed notifies data change and finishes pendingResult`() =
        runTest {
            var acquireCount = 0
            val receiver =
                object : LocaleChangedReceiver() {
                    override fun acquirePendingResult(): PendingResult {
                        acquireCount++
                        return mockPendingResult
                    }

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun provideDataChangeNotifier(appContext: Context): DataChangeNotifier =
                        mockNotifier
                }

            val intent = Intent(Intent.ACTION_LOCALE_CHANGED)
            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            assertEquals(1, acquireCount)
            verify(mockNotifier).notifyDataChanged(updateWidget = true, force = true)
            verify(mockPendingResult).finish()
        }

    @Test
    fun `onReceive finishes pendingResult even when notifier throws exception`() =
        runTest {
            whenever(
                mockNotifier.notifyDataChanged(true, true),
            ).thenThrow(RuntimeException("Storage error"))

            val receiver =
                object : LocaleChangedReceiver() {
                    override fun acquirePendingResult(): PendingResult = mockPendingResult

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun provideDataChangeNotifier(appContext: Context): DataChangeNotifier =
                        mockNotifier
                }

            val intent = Intent(Intent.ACTION_LOCALE_CHANGED)
            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            verify(mockPendingResult).finish()
        }

    @Test
    fun `onReceive rethrows CancellationException and finishes pendingResult`() =
        runTest {
            whenever(
                mockNotifier.notifyDataChanged(true, true),
            ).thenThrow(CancellationException("Cancelled"))

            val receiver =
                object : LocaleChangedReceiver() {
                    override fun acquirePendingResult(): PendingResult = mockPendingResult

                    override fun asyncDispatcher(): CoroutineDispatcher = mainDispatcherRule.testDispatcher

                    override suspend fun provideDataChangeNotifier(appContext: Context): DataChangeNotifier =
                        mockNotifier
                }

            val intent = Intent(Intent.ACTION_LOCALE_CHANGED)
            receiver.onReceive(context, intent)

            advanceUntilIdle()
            ShadowLooper.idleMainLooper()

            verify(mockPendingResult).finish()
        }

    @Test
    fun manifestContainsCorrectLocaleChangedAction() {
        val component = ComponentName(context, LocaleChangedReceiver::class.java)

        try {
            context.packageManager.getReceiverInfo(component, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw AssertionError("LocaleChangedReceiver not found in manifest", e)
        }

        val expectedAction = Intent.ACTION_LOCALE_CHANGED
        val intent = Intent(expectedAction)
        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)

        val found =
            receivers.any {
                it.activityInfo.packageName == context.packageName &&
                    it.activityInfo.name == LocaleChangedReceiver::class.java.name
            }

        assertTrue(
            "Manifest for LocaleChangedReceiver must match action $expectedAction, but it doesn't.",
            found,
        )
    }
}
