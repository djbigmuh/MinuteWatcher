package com.example.minutewatcher.feature.semantic.search

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.util.SemanticMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

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
}
