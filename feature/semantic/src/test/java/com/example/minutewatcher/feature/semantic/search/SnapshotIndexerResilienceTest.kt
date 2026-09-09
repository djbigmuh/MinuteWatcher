package com.example.minutewatcher.feature.semantic.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotIndexerResilienceTest : SemanticSearchManagerTestFixtures() {
    @Test
    fun `performIndexUpdate skips processing for empty snapshot`() =
        runTest(testDispatcher) {
            val appSearchWrapper: AppSearchWrapper = mock()
            val errorHandler: AppSearchErrorHandler = mock()
            val dataStore: DataStore<Preferences> = mock()
            val indexer = SnapshotIndexer(appSearchWrapper, errorHandler, dataStore)
            val session: AppSearchSessionWrapper = mock()

            val emptySnapshot =
                GlobalSnapshot(
                    version = SchemaVersion(1, 0, 0),
                    timestamp = 0L,
                    simSnapshots = emptyList(),
                    capabilities = emptyList(),
                )

            indexer.performIndexUpdate(session, emptySnapshot)

            verify(session, never()).putDocuments(any())
            verify(session, never()).removeByDocumentId(any())
        }

    @Test
    fun `performIndexUpdate reorders operations and handles partial remove failure`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)

            // 1. Initial index to have some "stale" documents later
            manager.indexSnapshot(createSnapshotWithOneSim())
            val session = fakeWrapper.lastSession!!

            // 2. Setup remove failure but success for put
            fakeWrapper.failAllRemoveByDocumentId = true
            fakeWrapper.failAllPutDocuments = false

            // 3. Index new snapshot (triggers remove sim_0 and put sim_1)
            val snapshotWithSim1 =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            slot = 1,
                            identity = "sim_1",
                            operatorName = "B",
                            entities =
                                mapOf(
                                    PackageType.MINUTES to createEntity(1, PackageType.MINUTES, 50),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                )

            val result = manager.indexSnapshot(snapshotWithSim1)

            // Assertions based on new requirements:
            // Fails indexing if remove fails
            assertTrue(
                "indexSnapshot should return ActualFailure if remove fails",
                result is SemanticSearchManager.IndexResult.ActualFailure,
            )
            assertTrue("Status should be Error", manager.status.value is TrackingStatus.Error)

            // Verify that put WAS called (since put happens before remove now)
            assertEquals("Should have initial put and new put request", 2, session.putRequests.size)
        }

    @Test
    fun `performIndexUpdate fails if putDocuments fails`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)

            // Setup put failure
            fakeWrapper.failAllPutDocuments = true

            val result = manager.indexSnapshot(createSnapshotWithOneSim())

            // Assertions:
            assertTrue(
                "indexSnapshot should return ActualFailure if put fails",
                result is SemanticSearchManager.IndexResult.ActualFailure,
            )
            assertTrue("Status should be Error", manager.status.value is TrackingStatus.Error)
        }
}
