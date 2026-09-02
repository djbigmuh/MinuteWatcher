package com.example.minutewatcher.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.minutewatcher.core.common.testing.MainDispatcherRule
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.data.di.DataStoreKeys
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SimIdentityPersistenceFailureTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val migrationService: IdentityMigrationService = mock()

    @Before
    fun setUp() {
        initTestLogger()
    }

    @Test
    fun `migration failure allows retry and prevents DataStore update`() =
        runTest {
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = {
                        tmpFolder.newFile("test_ds_failure.preferences_pb")
                    },
                )
            val persistence = SimIdentityPersistence(dataStore, migrationService)
            val identity = SimIdentity("stable_id", 101, 1, IdentitySource.SUBSCRIPTION_ID, false)

            // 1. Setup migration to fail
            whenever(migrationService.migrateLegacyData(identity)).thenAnswer {
                throw IOException("Mock failure")
            }

            // 2. Call saveSimIdentitiesInfo - it should catch the exception internally but log it
            persistence.saveSimIdentitiesInfo(listOf(identity))
            advanceUntilIdle()

            // 3. Verify migration was called
            verify(migrationService, times(1)).migrateLegacyData(identity)

            // 4. Verify DataStore was NOT updated for this identity (slotIndex should be null)
            val prefs = dataStore.data.first()
            assertNull(
                "Slot index should not be saved if migration failed",
                prefs[DataStoreKeys.slotIndexKey(identity.stableId)],
            )

            // 5. Setup migration to succeed now
            reset(migrationService)
            whenever(migrationService.migrateLegacyData(identity))
                .doReturn(IdentityMigrationService.MigrationOutcome(migrated = 1))

            // 6. Call saveSimIdentitiesInfo again
            persistence.saveSimIdentitiesInfo(listOf(identity))
            advanceUntilIdle()

            // 7. Verify migration was called AGAIN (retry)
            verify(migrationService, times(1)).migrateLegacyData(identity)

            // 8. Verify DataStore IS updated now
            val finalPrefs = dataStore.data.first()
            assertEquals(1, finalPrefs[DataStoreKeys.slotIndexKey(identity.stableId)])
        }
}
