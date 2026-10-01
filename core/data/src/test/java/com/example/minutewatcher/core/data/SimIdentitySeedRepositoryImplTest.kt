package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.domain.SeedResolutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentitySeedRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SimIdentitySeedRepositoryImpl
    private val testScope = TestScope(mainDispatcherRule.testDispatcher)

    @Before
    fun setup() {
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
            ) { tmpFolder.newFile("test_seed.preferences_pb") }
        repository =
            SimIdentitySeedRepositoryImpl(
                dataStore = dataStore,
            )
    }

    @Test
    fun `getOrCreateSeed is atomic`() =
        runTest {
            val results =
                (1..50)
                    .map {
                        async {
                            repository.getOrCreateSeedOrEphemeralFallback { "seed-$it" }.seed
                        }
                    }.awaitAll()

            // All calls should return the same seed (the one that won the race)
            assertEquals(1, results.toSet().size)
        }

    @Test
    fun `getOrCreateSeed falls back to ephemeral on storage failure`() =
        runTest {
            val failingDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> =
                        flow {
                            throw IOException("Test failure")
                        }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                        throw IOException("Test failure")
                }

            val repo = SimIdentitySeedRepositoryImpl(failingDataStore)

            val result = repo.getOrCreateSeedOrEphemeralFallback { "ephemeral-seed" }
            assertTrue(result is SeedResolutionResult.Ephemeral)
            assertEquals("ephemeral-seed", result.seed)
            assertTrue(repo.isEphemeralSeedActive.value)
        }

    @Test
    fun `isEphemeralSeedActive is reset when persistent seed is restored`() =
        runTest {
            var shouldFail = true
            val toggleDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> =
                        flow {
                            if (shouldFail) {
                                throw IOException("Storage unavailable")
                            }
                            dataStore.data.collect { emit(it) }
                        }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        if (shouldFail) {
                            throw IOException("Storage unavailable")
                        }
                        return dataStore.updateData(transform)
                    }
                }

            val repo = SimIdentitySeedRepositoryImpl(toggleDataStore)

            // 1. Storage fails -> Enter ephemeral mode
            val resultEph = repo.getOrCreateSeedOrEphemeralFallback { "ephemeral" }
            assertTrue(resultEph is SeedResolutionResult.Ephemeral)
            assertTrue(repo.isEphemeralSeedActive.value)

            // 2. Storage restored -> Re-run
            shouldFail = false
            val resultPersist = repo.getOrCreateSeedOrEphemeralFallback { "ephemeral" }
            assertTrue(resultPersist is SeedResolutionResult.Persistent)
            // Flag should be reset
            assertTrue(
                "isEphemeralSeedActive should be false after persistent seed restored",
                !repo.isEphemeralSeedActive.value,
            )
        }

    @Test
    fun `setSeed does not wait for retry delays`() =
        runTest {
            var shouldFail = true
            val failingDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> =
                        flow {
                            if (shouldFail) {
                                throw IOException("Storage unavailable")
                            }
                            dataStore.data.collect { emit(it) }
                        }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        if (shouldFail) {
                            throw IOException("Storage unavailable")
                        }
                        return dataStore.updateData(transform)
                    }
                }

            val repo = SimIdentitySeedRepositoryImpl(failingDataStore)

            // 1. Start getOrCreateSeed... which will fail and delay
            val firstCall =
                launch {
                    repo.getOrCreateSeedOrEphemeralFallback { "ephemeral" }
                }

            // Let it start and hit the first failure/delay
            mainDispatcherRule.testDispatcher.scheduler.runCurrent()

            // 2. Now call setSeed. It should succeed immediately if we make storage work
            shouldFail = false

            val setSeedJob =
                launch {
                    repo.setSeed("new-persistent-seed")
                }

            // setSeed should complete quickly
            mainDispatcherRule.testDispatcher.scheduler.runCurrent()
            assertTrue("setSeed should have completed", setSeedJob.isCompleted)

            withTimeoutOrNull(30.seconds) { firstCall.join() }
                ?: throw AssertionError("Timed out waiting for firstCall")
        }

    @Test
    fun `regression - no phantom persistent seed on commit failure`() =
        runTest {
            var updateDataCalls = 0
            val failingDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = flowOf(emptyPreferences())

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        updateDataCalls++
                        // Transform is called, but we throw exception on commit
                        transform(emptyPreferences())
                        throw IOException("Commit failed")
                    }
                }

            val repo = SimIdentitySeedRepositoryImpl(failingDataStore)

            // Act: Try to get seed. It should retry 3 times and then fallback to Ephemeral.
            // In the buggy code, it would have returned Persistent after the first attempt.
            val result = repo.getOrCreateSeedOrEphemeralFallback { "gen-seed" }

            // Assert
            assertTrue(
                "Result should be Ephemeral because all persistent attempts failed",
                result is SeedResolutionResult.Ephemeral,
            )
            assertEquals("Should have attempted edit 3 times", 3, updateDataCalls)
        }

    @Test
    fun `regression - existing seed is read without edit transaction`() =
        runTest {
            val existingSeed = "existing-persistent-seed"
            val prefs = emptyPreferences().toMutablePreferences()
            prefs[DataStoreKeys.SIM_IDENTITY_SEED] = existingSeed

            var updateDataCalls = 0
            val countingDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = flowOf(prefs.toPreferences())

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        updateDataCalls++
                        return transform(prefs.toPreferences())
                    }
                }

            val repo = SimIdentitySeedRepositoryImpl(countingDataStore)

            // Act
            val result = repo.getOrCreateSeedOrEphemeralFallback { "gen-seed" }

            // Assert
            assertTrue(result is SeedResolutionResult.Persistent)
            assertEquals(existingSeed, result.seed)
            assertEquals("updateData (edit) should NOT have been called for reading existing seed", 0, updateDataCalls)
        }

    @Test
    fun `getOrCreateSeedOrEphemeralFallback does not hang when DataStore hangs`() =
        runTest {
            val hangingDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = MutableSharedFlow()

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        val deferred = CompletableDeferred<Preferences>()
                        return withTimeoutOrNull(30.seconds) { deferred.await() }
                            ?: throw AssertionError("Timed out waiting for hangingDataStore updateData")
                    }
                }

            val repo = SimIdentitySeedRepositoryImpl(hangingDataStore)

            var result: SeedResolutionResult? = null
            val job =
                launch {
                    result = repo.getOrCreateSeedOrEphemeralFallback { "ephemeral-fallback-seed" }
                }

            advanceTimeBy(20_000.milliseconds)
            runCurrent()

            assertTrue("Job should complete within bounded virtual time", job.isCompleted)
            assertTrue("Result should be Ephemeral due to DataStore hang", result is SeedResolutionResult.Ephemeral)
            assertEquals("ephemeral-fallback-seed", result?.seed)
        }

    @Test
    fun `getOrCreateEphemeralSeed returns same seed and cache is cleared on setSeed or persistent restored`() =
        runTest {
            val seed1 = repository.getOrCreateEphemeralSeed { "eph-1" }
            val seed2 = repository.getOrCreateEphemeralSeed { "eph-2" }
            assertEquals(seed1, seed2)
            assertEquals("eph-1", seed1)

            repository.setSeed("persistent-seed")
            val seed3 = repository.getOrCreateEphemeralSeed { "eph-3" }
            assertEquals("eph-3", seed3)

            var shouldFail = true
            val toggleDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> =
                        flow {
                            if (shouldFail) {
                                throw IOException("Storage unavailable")
                            }
                            dataStore.data.collect { emit(it) }
                        }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        if (shouldFail) {
                            throw IOException("Storage unavailable")
                        }
                        return dataStore.updateData(transform)
                    }
                }

            val repo = SimIdentitySeedRepositoryImpl(toggleDataStore)
            val s1 = repo.getOrCreateEphemeralSeed { "eph-a" }
            assertEquals("eph-a", s1)

            shouldFail = false
            repo.getOrCreateSeedOrEphemeralFallback { "persist-seed" }

            val s2 = repo.getOrCreateEphemeralSeed { "eph-b" }
            assertEquals("eph-b", s2)
        }
}
