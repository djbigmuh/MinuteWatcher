package host.serv.djbigmuh.minutewatcher.feature.semantic.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import host.serv.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SchemaVersion
import host.serv.djbigmuh.minutewatcher.core.model.SimSnapshot
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

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
            val manager = createManager(appScope)

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
            // Does NOT fail indexing if remove fails; document writing succeeds
            assertTrue(
                "indexSnapshot should return Success or PartialSuccess even if remove fails",
                result is SemanticSearchManager.IndexResult.Success ||
                    result is SemanticSearchManager.IndexResult.PartialSuccess,
            )

            // Verify that put WAS called
            assertEquals("Should have initial put and new put request", 2, session.putRequests.size)
        }

    @Test
    fun `performIndexUpdate fails if putDocuments fails`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)

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
