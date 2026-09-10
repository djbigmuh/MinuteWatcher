package com.example.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AutoResetUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AutoResetSchedulerTest {
    private val savedStateHandle = SavedStateHandle()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val timeProvider: TimeProvider = mock()
    private lateinit var scheduler: AutoResetScheduler

    @Before
    fun setup() {
        val context: android.content.Context = mock()
        whenever(context.filesDir).thenReturn(java.io.File("/tmp"))
        com.example.minutewatcher.core.common.util.AppLogger
            .init(context, mock())

        scheduler = AutoResetScheduler(savedStateHandle, autoResetUseCase, timeProvider)
        whenever(timeProvider.elapsedRealtime()).thenReturn(10_000_000L)
    }

    @Test
    fun `checkOnStart can be retried after RuntimeException`() =
        runTest {
            // Setup consecutive calls: first fails, second succeeds
            whenever(autoResetUseCase.performAutoReset(any()))
                .thenThrow(RuntimeException("Fail"))
                .thenReturn(true)

            // First attempt - fails
            scheduler.checkOnStart()
            verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(value = false))
            assertEquals(1, savedStateHandle.get<Int>("auto_reset_fail_count"))

            // Second attempt - should try again and succeed
            scheduler.checkOnStart()
            verify(autoResetUseCase, times(2)).performAutoReset(shouldNotify = eq(value = false))
            assertEquals(0, savedStateHandle.get<Int>("auto_reset_fail_count"))
        }

    @Test(expected = CancellationException::class)
    fun `checkOnStart does not reset flag after CancellationException`() =
        runTest {
            whenever(autoResetUseCase.performAutoReset(any())).thenAnswer { throw CancellationException("Cancel") }

            try {
                scheduler.checkOnStart()
            } catch (e: CancellationException) {
                // Second attempt - should NOT try again because flag is still true
                scheduler.checkOnStart()
                verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(value = false))
                throw e
            }
        }

    @Test
    fun `checkOnStart does not run twice on success`() =
        runTest {
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)

            scheduler.checkOnStart()
            verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(value = false))

            scheduler.checkOnStart()
            verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(value = false))
        }
}
