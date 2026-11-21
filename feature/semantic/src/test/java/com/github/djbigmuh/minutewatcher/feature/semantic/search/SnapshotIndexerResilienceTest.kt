package com.github.djbigmuh.minutewatcher.feature.semantic.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.github.djbigmuh.minutewatcher.core.model.AppSettings
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SchemaVersion
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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

    @Test
    fun `assistant access disabled skips indexing and clears index`() =
        runTest(testDispatcher) {
            whenever(appSettingsRepository.getAppSettings())
                .thenReturn(
                    flowOf(
                        AppSettings(
                            assistantAccessEnabled = false,
                            isLoaded = true,
                        ),
                    ),
                )

            val manager = createManager(appScope)
            val result = manager.indexSnapshot(createSnapshotWithOneSim())

            assertTrue(
                "Result should be Success when assistant access disabled",
                result is SemanticSearchManager.IndexResult.Success,
            )
            val session = fakeWrapper.lastSession
            assertTrue(
                "Session should have set schema with forceOverride",
                session?.lastSchemaRequest?.isForceOverride == true,
            )
        }

    @Test
    fun `error to recovery scenario with final state check`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)

            // Force error state
            fakeWrapper.failAllPutDocuments = true
            manager.indexSnapshot(createSnapshotWithOneSim())
            assertTrue(manager.status.value is TrackingStatus.Error)

            // Recover
            fakeWrapper.failAllPutDocuments = false
            manager.recover()

            // Index again successfully
            val result = manager.indexSnapshot(createSnapshotWithOneSim())
            assertTrue(result is SemanticSearchManager.IndexResult.Success)
            assertTrue("Final state should be Ready", manager.status.value is TrackingStatus.Ready)
        }
}
