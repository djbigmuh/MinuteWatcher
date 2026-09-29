package com.example.minutewatcher.feature.main.controller

import com.example.minutewatcher.core.common.util.FixedTimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.domain.AutoResetScheduleRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AutoResetSchedulerTest {
    private val fakeRepository = FakeAutoResetScheduleRepository()
    private val autoResetUseCase: AutoResetUseCase = mock()
    private val timeProvider = FixedTimeProvider(currentTime = 10_000_000L)
    private lateinit var scheduler: AutoResetScheduler

    @Before
    fun setup() {
        initTestLogger()
        scheduler = AutoResetScheduler(fakeRepository, autoResetUseCase, timeProvider)
    }

    @Test
    fun `checkOnStart runs when no check time is saved`() =
        runTest {
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)

            scheduler.checkOnStart()

            verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(false))
            assertEquals(10_000_000L, fakeRepository.lastCheckMillis)
            assertEquals(0, fakeRepository.failCount)
        }

    @Test
    fun `checkOnStart skips reset when saved time is less than check interval ago`() =
        runTest {
            fakeRepository.lastCheckMillis = 10_000_000L - 1_800_000L // 30 minutes ago
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)

            scheduler.checkOnStart()

            verify(autoResetUseCase, never()).performAutoReset(any())
        }

    @Test
    fun `checkOnStart performs reset when saved time is more than check interval ago`() =
        runTest {
            fakeRepository.lastCheckMillis = 10_000_000L - 4_000_000L // > 1 hour ago
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)

            scheduler.checkOnStart()

            verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(false))
            assertEquals(10_000_000L, fakeRepository.lastCheckMillis)
        }

    @Test
    fun `checkOnStart performs reset when system clock was moved backwards`() =
        runTest {
            fakeRepository.lastCheckMillis = 10_000_000L + 5_000_000L // Future timestamp
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)

            scheduler.checkOnStart()

            verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(false))
            assertEquals(10_000_000L, fakeRepository.lastCheckMillis)
        }

    @Test
    fun `checkOnStart skips reset when retries exceed max failures but updates check time`() =
        runTest {
            fakeRepository.lastCheckMillis = 10_000_000L - 4_000_000L
            fakeRepository.failCount = 4 // Exceeds MAX_AUTO_RESET_RETRIES (3)

            scheduler.checkOnStart()

            verify(autoResetUseCase, never()).performAutoReset(any())
            assertEquals(10_000_000L, fakeRepository.lastCheckMillis)
        }

    @Test
    fun `checkOnStart resets fail count after fail window expires and performs reset`() =
        runTest {
            fakeRepository.lastCheckMillis = 10_000_000L - (25 * 3_600_000L) // > 24 hours ago
            fakeRepository.failCount = 4
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)

            scheduler.checkOnStart()

            verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(false))
            assertEquals(0, fakeRepository.failCount)
            assertEquals(10_000_000L, fakeRepository.lastCheckMillis)
        }

    @Test
    fun `checkOnStart does not run twice on same scheduler instance`() =
        runTest {
            whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)

            scheduler.checkOnStart()
            verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(false))

            scheduler.checkOnStart()
            verify(autoResetUseCase, times(1)).performAutoReset(shouldNotify = eq(false))
        }

    @Test
    fun `checkOnStart increments failure count and resets isCheckStarted on Exception`() =
        runTest {
            whenever(autoResetUseCase.performAutoReset(any()))
                .thenThrow(RuntimeException("Failure"))
                .thenReturn(true)

            // Attempt 1 fails
            scheduler.checkOnStart()
            assertEquals(1, fakeRepository.failCount)

            // Attempt 2 can be retried because isCheckStarted was reset
            scheduler.checkOnStart()
            verify(autoResetUseCase, times(2)).performAutoReset(shouldNotify = eq(false))
            assertEquals(0, fakeRepository.failCount)
        }

    @Test(expected = CancellationException::class)
    fun `checkOnStart rethrows CancellationException and resets flag for retry`() =
        runTest {
            whenever(autoResetUseCase.performAutoReset(any())).thenAnswer { throw CancellationException("Cancel") }

            try {
                scheduler.checkOnStart()
            } catch (e: CancellationException) {
                // Retry attempt
                whenever(autoResetUseCase.performAutoReset(any())).thenReturn(true)
                scheduler.checkOnStart()
                verify(autoResetUseCase, times(2)).performAutoReset(shouldNotify = eq(false))
                throw e
            }
        }

    private class FakeAutoResetScheduleRepository : AutoResetScheduleRepository {
        var lastCheckMillis: Long? = null
        var failCount: Int = 0

        override suspend fun getLastCheckMillis(): Long? = lastCheckMillis

        override suspend fun setLastCheckMillis(valueMillis: Long) {
            lastCheckMillis = valueMillis
        }

        override suspend fun getFailureCount(): Int = failCount

        override suspend fun setFailureCount(count: Int) {
            failCount = count
        }
    }
}
