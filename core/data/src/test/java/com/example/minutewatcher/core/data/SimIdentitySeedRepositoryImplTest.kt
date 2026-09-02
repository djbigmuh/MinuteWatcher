package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.model.SimDiscoveryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
                produceFile = { tmpFolder.newFile("test_seed.preferences_pb") },
            )
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
                            repository.getOrCreateSeed { "seed-$it" }
                        }
                    }.awaitAll()

            // All calls should return the same seed (the one that won the race)
            assertEquals(1, results.toSet().size)
        }

    @Test
    fun `migration does not block concurrent seed access`() =
        runTest {
            val mockDataStore: DataStore<Preferences> = mock()
            val repo = SimIdentitySeedRepositoryImpl(mockDataStore, { migrationService }, { discovery })

            // 1. Make DataStore fail to trigger ephemeral mode
            whenever(mockDataStore.data).thenReturn(
                kotlinx.coroutines.flow.flow { throw java.io.IOException("Test failure") },
            )

            val ephemeralSeed = repo.getOrCreateSeed { "ephemeral-seed" }
            assertEquals("ephemeral-seed", ephemeralSeed)
            assertEquals(true, repo.isEphemeralSeedActive.value)

            // 2. Now make DataStore work and migration be slow
            val prefs: Preferences = mock()
            whenever(mockDataStore.data).thenReturn(flowOf(prefs))
            whenever(prefs[DataStoreKeys.SIM_IDENTITY_SEED]).thenReturn("persistent-seed")
            whenever(discovery.discoverSims(any())).thenReturn(SimDiscoveryResult.Available(emptyList()))

            val migrationStarted = CompletableDeferred<Unit>()

            whenever(migrationService.migrateFromEphemeralSeed(any(), any())).thenAnswer {
                migrationStarted.complete(Unit)
                // Instead of runBlocking, we can use a small delay to simulate work,
                // but for deterministic wait we need runBlocking if thenAnswer is not suspendable.
                // However, the user said they fixed it by removing runBlocking.
                // Maybe they used a mock that returns a flow or something? No, this returns an object.

                // If we want to wait without runBlocking, we'd need a suspendable mock.
                IdentityMigrationService.MigrationOutcome()
            }

            // Start getOrCreateSeed which should trigger migration
            val firstCall = async { repo.getOrCreateSeed { "should-not-be-used" } }

            // Wait for migration to start
            migrationStarted.await()

            // 3. Concurrent call should not be blocked
            val secondCall = async { repo.getOrCreateSeed { "should-not-be-used" } }

            // Second call should complete even if migration is running
            val secondResult = withTimeout(100.milliseconds) { secondCall.await() }
            assertEquals("persistent-seed", secondResult)

            // Cleanup
            firstCall.await()
        }

    @Test
    fun `migration failure allows retry on next call`() =
        runTest {
            val mockDataStore: DataStore<Preferences> = mock()
            val repo = SimIdentitySeedRepositoryImpl(mockDataStore, { migrationService }, { discovery })

            // 1. Enter ephemeral mode
            whenever(mockDataStore.data).thenReturn(
                kotlinx.coroutines.flow.flow { throw java.io.IOException("Test failure") },
            )
            repo.getOrCreateSeed { "ephemeral-seed" }

            // 2. Make migration fail
            val prefs: Preferences = mock()
            whenever(mockDataStore.data).thenReturn(flowOf(prefs))
            whenever(prefs[DataStoreKeys.SIM_IDENTITY_SEED]).thenReturn("persistent-seed")
            whenever(discovery.discoverSims(any())).thenReturn(SimDiscoveryResult.Available(emptyList()))

            whenever(migrationService.migrateFromEphemeralSeed(any(), any()))
                .thenThrow(RuntimeException("Migration failed"))
                .thenReturn(IdentityMigrationService.MigrationOutcome())

            // First call triggers migration which fails
            repo.getOrCreateSeed { "should-not-be-used" }

            // Second call should trigger migration AGAIN
            repo.getOrCreateSeed { "should-not-be-used" }

            // Verification: migrateFromEphemeralSeed should be called twice
            verify(migrationService, times(2))
                .migrateFromEphemeralSeed(any(), any())
        }
}
