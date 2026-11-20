package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.initTestLogger
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryFailureReason
import com.github.djbigmuh.minutewatcher.core.model.SimDiscoveryResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveSimsFetcherTest {
    private val discovery: SimIdentityDiscovery = mock()
    private val persistence: SimIdentityPersistence = mock()
    private val cache: SimIdentityCache = mock()

    private lateinit var fetcher: ActiveSimsFetcher

    @Before
    fun setup() {
        initTestLogger()
        fetcher = ActiveSimsFetcher(discovery, persistence, cache)
    }

    @Test
    fun `fetchActiveSimsWithRetry returns STORAGE_ERROR when all attempts throw IOException`() =
        runTest {
            val seed = SeedResult.Real("test_seed", isStable = true)
            whenever(discovery.discoverSims(any(), any())).thenAnswer { throw IOException("Disk error") }

            val result = fetcher.fetchActiveSimsWithRetry(seed)

            assertTrue(result is SimDiscoveryResult.Failed)
            assertEquals(SimDiscoveryFailureReason.STORAGE_ERROR, (result as SimDiscoveryResult.Failed).reason)
            verify(discovery, times(5)).discoverSims(any(), any())
            assertEquals(15000, testScheduler.currentTime)
        }

    @Test
    fun `fetchActiveSimsWithRetry retries on transient IOException and succeeds`() =
        runTest {
            val seed = SeedResult.Real("test_seed", isStable = true)
            var attempts = 0
            whenever(discovery.discoverSims(any(), any())).thenAnswer {
                attempts++
                if (attempts < 3) {
                    throw IOException("Transient error")
                } else {
                    SimDiscoveryResult.Available(emptyList())
                }
            }

            val result = fetcher.fetchActiveSimsWithRetry(seed)

            assertTrue(result is SimDiscoveryResult.Available)
            assertEquals(3, attempts)
            assertEquals(3000, testScheduler.currentTime)
        }
}
