package com.example.minutewatcher.feature.semantic.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.util.SemanticMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotIndexerCleanupTest : SemanticSearchManagerTestFixtures() {
    @Test
    fun `cleanupStalePackageTypes removes documents for stale types using SemanticMapper`() =
        runTest(testDispatcher) {
            val appSearchWrapper = FakeAppSearchWrapper()
            val errorHandler = AppSearchErrorHandler()
            val indexer = SnapshotIndexer(appSearchWrapper, errorHandler, dataStore)

            // 1. Setup DataStore with a stale package type "OLD_TYPE"
            val key = stringSetPreferencesKey("appsearch_package_types")
            dataStore.edit { prefs ->
                val currentTypes = PackageType.entries.map { it.name }.toSet()
                prefs[key] = currentTypes + "OLD_TYPE"
            }

            // 2. Perform cleanup
            val sessionManager = AppSearchSessionManager(mock(), appSearchWrapper, errorHandler)
            indexer.cleanupStalePackageTypes(sessionManager) { /* no-op status update */ }
            testScheduler.advanceUntilIdle()

            // 3. Verify that removeByDocumentId was called for "OLD_TYPE" for all slots
            val session = appSearchWrapper.lastSession
            assertTrue("Session should have been created", session != null)
            val lastRemove = session!!.removeRequests.last()

            (0 until 10).forEach { slot ->
                val expectedId = SemanticMapper.getDocumentId(slot, "OLD_TYPE")
                assertTrue("Should remove $expectedId", lastRemove.ids.contains(expectedId))
            }
        }

    @Test
    fun `cleanupStalePackageTypes when DataStore read times out completes without calling sessionManager`() =
        runTest(testDispatcher) {
            val appSearchWrapper = FakeAppSearchWrapper()
            val errorHandler = AppSearchErrorHandler()
            val hangingDataStore = mock<DataStore<Preferences>>()
            whenever(hangingDataStore.data).thenReturn(emptyFlow())

            val indexer = SnapshotIndexer(appSearchWrapper, errorHandler, hangingDataStore)
            val mockSessionManager = mock<AppSearchSessionManager>()

            indexer.cleanupStalePackageTypes(mockSessionManager) { }
            testScheduler.advanceTimeBy(Constants.Flow.SHORT_TIMEOUT_MS + 100)

            verify(mockSessionManager, never()).ensureSession(any(), eq(false))
            assertNull("No session should be created", appSearchWrapper.lastSession)
        }

    @Test
    fun `cleanupStalePackageTypes when DataStore edit times out completes without throwing exception`() =
        runTest(testDispatcher) {
            val appSearchWrapper = FakeAppSearchWrapper()
            val errorHandler = AppSearchErrorHandler()
            var editAttempted = false
            val blockingEditDataStore =
                object : DataStore<Preferences> {
                    override val data = flowOf(emptyPreferences())

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        editAttempted = true
                        delay((Constants.Flow.SHORT_TIMEOUT_MS * 2).milliseconds)
                        return emptyPreferences()
                    }
                }

            val indexer = SnapshotIndexer(appSearchWrapper, errorHandler, blockingEditDataStore)
            val sessionManager = AppSearchSessionManager(mock(), appSearchWrapper, errorHandler)

            indexer.cleanupStalePackageTypes(sessionManager) { }
            testScheduler.advanceTimeBy(Constants.Flow.SHORT_TIMEOUT_MS * 2)

            assertTrue("DataStore edit should have been attempted", editAttempted)
            assertNull("No session should be created", appSearchWrapper.lastSession)
        }
}
