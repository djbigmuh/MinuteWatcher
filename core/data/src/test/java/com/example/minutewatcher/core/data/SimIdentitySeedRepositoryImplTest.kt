package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.domain.SeedResolutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

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

            firstCall.join()
        }
}
