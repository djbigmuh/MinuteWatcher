package com.example.minutewatcher.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoundedWaitResilienceTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test(expected = IOException::class)
    fun `saveSimIdentitiesInfo throws IOException on DataStore hang`() =
        runTest {
            val hangingDataStore: DataStore<Preferences> = mock()
            whenever(hangingDataStore.data).thenReturn(MutableSharedFlow())

            val persistence = SimIdentityPersistence(hangingDataStore, mock())
            val identities = listOf(SimIdentity("id", 1, 0, IdentitySource.ICCID, isStable = true))

            // Act
            persistence.saveSimIdentitiesInfo(identities)
        }

    @Test
    fun `hasLegacyData returns null on DataStore hang`() =
        runTest {
            val hangingDataStore: DataStore<Preferences> = mock()
            whenever(hangingDataStore.data).thenReturn(MutableSharedFlow())

            val service = IdentityMigrationService(hangingDataStore, hangingDataStore)

            val result = service.hasLegacyData()
            Assert.assertNull(result)
        }

    @Test(expected = IOException::class)
    fun `migrateLegacyData rethrows IOException when internal ops hang`() =
        runTest {
            val hangingSimDataStore: DataStore<Preferences> = mock()
            whenever(hangingSimDataStore.data).thenReturn(MutableSharedFlow())

            val service = IdentityMigrationService(mock(), hangingSimDataStore)
            val identity = SimIdentity("id", 1, 0, IdentitySource.SUBSCRIPTION_ID, isStable = true)

            service.migrateLegacyData(identity)
        }

    @Test
    fun `migrateDataStoreBatch returns partialFailure on edit hang`() =
        runTest {
            val hangingDataStore =
                object : DataStore<Preferences> {
                    override val data = MutableSharedFlow<Preferences>()

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                        delay(100.seconds)
                        return mock()
                    }
                }

            val service = IdentityMigrationService(hangingDataStore, hangingDataStore)

            val simInfo: ActiveSimInfo =
                mock {
                    on { slotIndex } doReturn 0
                    on { identity } doReturn SimIdentity("real", 1, 0, IdentitySource.ICCID, true)
                }

            // migrateFromEphemeralSeed uses migrateDataStoreBatch
            val outcome = service.migrateFromEphemeralSeed("seed", listOf(simInfo))

            assertTrue("Should report partial failure on timeout", outcome.partialFailure)
            assertTrue("Should report failures count", outcome.failures > 0)
        }

    @Test
    fun `markSchemaCurrent does not throw but logs error on hang`() =
        runTest {
            val hangingDataStore =
                object : DataStore<Preferences> {
                    override val data = MutableSharedFlow<Preferences>()

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                        delay(100.seconds)
                        return mock()
                    }
                }
            val service = IdentityMigrationService(hangingDataStore, mock())

            // Act & Assert: should not throw
            service.markSchemaCurrent()
        }
}
