package com.github.djbigmuh.minutewatcher.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.common.testing.AppLoggerResetRule
import com.github.djbigmuh.minutewatcher.core.common.testing.MainDispatcherRule
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.Logger
import com.github.djbigmuh.minutewatcher.core.common.util.NoOpLogger
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
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

    @get:Rule
    val loggerResetRule = AppLoggerResetRule()

    @Test
    fun `saveSimIdentitiesInfo throws IOException on DataStore hang`() =
        runTest {
            val hangingDataStore: DataStore<Preferences> = mock()
            whenever(hangingDataStore.data).thenReturn(MutableSharedFlow())

            val persistence = SimIdentityPersistence(hangingDataStore, mock())
            val identities = listOf(SimIdentity("id", 1, 0, IdentitySource.ICCID, isStable = true))

            // Act
            val result = runCatching { persistence.saveSimIdentitiesInfo(identities) }
            assertTrue(result.exceptionOrNull() is IOException)
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

    @Test
    fun `migrateLegacyData rethrows IOException when internal ops hang`() =
        runTest {
            val hangingSimDataStore: DataStore<Preferences> = mock()
            whenever(hangingSimDataStore.data).thenReturn(MutableSharedFlow())

            val service = IdentityMigrationService(mock(), hangingSimDataStore)
            val identity = SimIdentity("id", 1, 0, IdentitySource.SUBSCRIPTION_ID, isStable = true)

            val result = runCatching { service.migrateLegacyData(identity) }
            assertTrue(result.exceptionOrNull() is IOException)
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
            val errorLogs = mutableListOf<String>()
            val capturingLogger =
                object : Logger by NoOpLogger() {
                    override fun e(
                        tag: String,
                        message: String,
                        throwable: Throwable?,
                    ) {
                        errorLogs.add(message)
                    }
                }
            val mockContext: Context =
                mock {
                    on { filesDir } doReturn null
                }
            AppLogger.init(mockContext, capturingLogger)

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

            val hasTimeoutLog =
                errorLogs.any {
                    it.contains("Timeout") || it.contains("timed out")
                }
            assertTrue("Expected timeout error log in AppLogger", hasTimeoutLog)
        }
}
