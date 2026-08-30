package com.example.minutewatcher.feature.semantic.search

import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotIndexerResilienceTest : SemanticSearchManagerTestFixtures() {
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
                            isActive = true,
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
            assertTrue("indexSnapshot should return false if remove fails", !result)
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
            assertTrue("indexSnapshot should return false if put fails", !result)
            assertTrue("Status should be Error", manager.status.value is TrackingStatus.Error)
        }
}
