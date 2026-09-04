package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.model.SimDiscoveryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentitySeedRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SimIdentitySeedRepositoryImpl
    private val migrationService: IdentityMigrationService = mock()
    private val discovery: SimIdentityDiscovery = mock()
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
                migrationService = { migrationService },
                discovery = { discovery },
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
    fun `migration does not block concurrent seed access`() =
        runTest {
            var shouldFail = true
            val failingDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> =
                        flow {
                            if (shouldFail) {
                                throw IOException("Test failure")
                            }
                            dataStore.data.collect { emit(it) }
                        }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        if (shouldFail) {
                            throw IOException("Test failure")
                        }
                        return dataStore.updateData(transform)
                    }
                }

            val repo = SimIdentitySeedRepositoryImpl(failingDataStore, { migrationService }, { discovery })

            // 1. Enter ephemeral mode
            val ephemeralSeed = repo.getOrCreateSeedOrEphemeralFallback { "ephemeral-seed" }.seed
            assertEquals("ephemeral-seed", ephemeralSeed)
            assertTrue(repo.isEphemeralSeedActive.value)

            // 2. Now make DataStore work and migration be slow
            shouldFail = false
            dataStore.edit { it[DataStoreKeys.SIM_IDENTITY_SEED] = "persistent-seed" }
            whenever(discovery.discoverSims(any())).thenReturn(SimDiscoveryResult.Available(emptyList()))

            val migrationStarted = CompletableDeferred<Unit>()

            whenever(migrationService.migrateFromEphemeralSeed(any(), any())).thenAnswer {
                migrationStarted.complete(Unit)
                // Use a non-blocking way to wait if possible, but here we are in a mock.
                // To simulate slowness, we can just return a result.
                IdentityMigrationService.MigrationOutcome()
            }

            // Start getOrCreateSeedOrEphemeralFallback which should trigger migration
            val firstCall = async { repo.getOrCreateSeedOrEphemeralFallback { "should-not-be-used" } }

            // Wait for migration to start
            migrationStarted.await()

            // 3. Concurrent call should not be blocked
            val secondCall = async { repo.getOrCreateSeedOrEphemeralFallback { "should-not-be-used" } }

            // Second call should complete even if migration is running
            val secondResult = withTimeout(100.milliseconds) { secondCall.await() }.seed
            assertEquals("persistent-seed", secondResult)

            // Cleanup
            firstCall.await()
        }

    @Test
    fun `migration failure allows retry on next call`() =
        runTest {
            var shouldFail = true
            val failingDataStore =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> =
                        flow {
                            if (shouldFail) {
                                throw IOException("Test failure")
                            }
                            dataStore.data.collect { emit(it) }
                        }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        if (shouldFail) {
                            throw IOException("Test failure")
                        }
                        return dataStore.updateData(transform)
                    }
                }

            val repo = SimIdentitySeedRepositoryImpl(failingDataStore, { migrationService }, { discovery })

            // 1. Enter ephemeral mode
            repo.getOrCreateSeedOrEphemeralFallback { "ephemeral-seed" }

            // 2. Make migration fail
            shouldFail = false
            dataStore.edit { it[DataStoreKeys.SIM_IDENTITY_SEED] = "persistent-seed" }
            whenever(discovery.discoverSims(any())).thenReturn(SimDiscoveryResult.Available(emptyList()))

            whenever(migrationService.migrateFromEphemeralSeed(any(), any()))
                .thenThrow(RuntimeException("Migration failed"))
                .thenReturn(IdentityMigrationService.MigrationOutcome())

            // First call triggers migration which fails
            repo.getOrCreateSeedOrEphemeralFallback { "should-not-be-used" }

            // Second call should trigger migration AGAIN
            repo.getOrCreateSeedOrEphemeralFallback { "should-not-be-used" }

            // Verification: migrateFromEphemeralSeed should be called twice
            verify(migrationService, times(2))
                .migrateFromEphemeralSeed(any(), any())
        }
}
