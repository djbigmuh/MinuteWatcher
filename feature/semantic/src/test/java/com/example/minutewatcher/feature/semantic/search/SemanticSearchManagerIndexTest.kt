package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.app.AppSearchResult
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchManagerIndexTest : SemanticSearchManagerTestFixtures() {
    @Test
    fun `indexSnapshot hash check skips identical data`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val s = createTestSnapshot(100)

            manager.indexSnapshot(s)
            val session = fakeWrapper.lastSession!!
            val initialPuts = session.putRequests.size

            manager.indexSnapshot(s)
            assertEquals(
                "Should skip due to identical hash",
                initialPuts,
                session.putRequests.size,
            )
        }

    @Test
    fun `indexSnapshot works with slot 1`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            slot = 1,
                            identity = "sim_1",
                            operatorName = "B",
                            entities =
                                mapOf(
                                    PackageType.DATA to createEntity(1, PackageType.DATA, 50),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                )
            manager.indexSnapshot(snapshot)

            val lastPut = fakeWrapper.lastSession!!.putRequests.last()
            assertTrue(lastPut.genericDocuments.isNotEmpty())
        }

    @Test
    fun `indexSnapshot with empty snapshot ignores update and does not clear index`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)

            // 1. Initial indexing
            manager.indexSnapshot(createSnapshotWithOneSim())
            val session = fakeWrapper.lastSession!!
            val initialRemoves = session.removeRequests.size

            // 2. Pass empty snapshot (timestamp = 0)
            val emptySnapshot = createSnapshot(emptyList()).copy(timestamp = 0L)
            manager.indexSnapshot(emptySnapshot)

            // 3. Verify NO additional remove requests
            assertEquals(
                "Should NOT have new remove requests",
                initialRemoves,
                session.removeRequests.size,
            )
        }

    @Test
    fun `empty snapshot triggers stale document removal`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)

            // 1. Index snapshot with one SIM
            manager.indexSnapshot(createSnapshotWithOneSim())
            val session = fakeWrapper.lastSession!!
            val initialRemoves = session.removeRequests.size

            // 2. Pass empty snapshot
            val result = manager.indexSnapshot(createSnapshot(emptyList()))

            assertTrue(
                "indexSnapshot should return Success for empty snapshot",
                result is SemanticSearchManager.IndexResult.Success,
            )
            assertEquals(
                "Should have one more remove request",
                initialRemoves + 1,
                session.removeRequests.size,
            )

            val lastRemove = session.removeRequests.last()
            assertTrue("Should remove sim_0_minutes", lastRemove.ids.contains("sim_0_minutes"))
            assertTrue("Should remove sim_0_data", lastRemove.ids.contains("sim_0_data"))
        }

    @Test
    fun `empty snapshot resets hash and allows re-indexing`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot = createSnapshotWithOneSim()

            // 1. Index snapshot
            manager.indexSnapshot(snapshot)
            val session = fakeWrapper.lastSession!!
            val initialPuts = session.putRequests.size

            // 2. Pass empty snapshot
            manager.indexSnapshot(createSnapshot(emptyList()))

            // 3. Pass SAME snapshot again
            manager.indexSnapshot(snapshot)

            assertEquals(
                "Should have re-indexed same data because hash was reset",
                initialPuts + 1,
                session.putRequests.size,
            )
        }

    @Test
    fun `indexSnapshot performs put but skips remove if put fails`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot = createSnapshotWithOneSim()

            // Setup failure for putDocuments
            fakeWrapper.failAllPutDocuments = true

            // Act: try indexing
            manager.indexSnapshot(snapshot)

            // Assert: verify put was attempted but failed, and remove was NOT called
            val session = fakeWrapper.lastSession!!
            assertTrue(
                "putDocuments should have failed (no successful requests recorded)",
                session.putRequests.isEmpty(),
            )
            assertEquals(
                "removeByDocumentId SHOULD NOT have been called",
                0,
                session.removeRequests.size,
            )
        }

    @Test
    fun `stale documents are NOT removed if putDocuments fails`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)

            // 1. Initial indexing
            manager.indexSnapshot(createSnapshotWithOneSim())
            val session = fakeWrapper.lastSession!!

            // 2. Setup failure for putDocuments
            fakeWrapper.failAllPutDocuments = true

            // 3. Create a snapshot with SIM 1, while SIM 0 was previously indexed.
            val snapshotWithSim1 =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            slot = 1,
                            identity = "sim_1",
                            operatorName = "B",
                            entities =
                                mapOf(
                                    PackageType.DATA to createEntity(1, PackageType.DATA, 50),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                )

            manager.indexSnapshot(snapshotWithSim1)

            // 4. Assert: verify put was attempted but failed, and remove was NOT called for sim_0
            assertEquals(
                "Total removeByDocumentId calls should still be 1 (from initial indexing)",
                1,
                session.removeRequests.size,
            )

            // Verify status is Error because put failed
            assertTrue(manager.status.value is TrackingStatus.Error)
        }

    @Test
    fun `indexSnapshot concurrent calls with same data results in single indexing`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot = createSnapshotWithOneSim()

            // Launch 5 concurrent indexing calls
            val jobs =
                List(5) {
                    launch {
                        manager.indexSnapshot(snapshot)
                    }
                }
            jobs.joinAll()

            // Verify that indexing was performed only once
            val session = fakeWrapper.lastSession
            assertNotNull("Session should have been created", session)
            assertEquals("Should have only one put request", 1, session!!.putRequests.size)
        }

    @Test
    fun `indexSnapshot concurrent calls with invalid schema results in single session recreation`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot = createSnapshotWithOneSim()

            // 1. Initial indexing success
            manager.indexSnapshot(snapshot)
            assertEquals(1, fakeWrapper.createdSessions)
            val session1 = fakeWrapper.lastSession!!

            // 2. Setup next indexing to fail with INVALID_SCHEMA
            session1.putDocumentsResultCode = AppSearchResult.RESULT_INVALID_SCHEMA

            // 3. Launch 5 concurrent indexing calls for NEW data
            val jobs =
                List(5) { i ->
                    launch {
                        manager.indexSnapshot(createTestSnapshot(101L + i))
                    }
                }
            jobs.joinAll()

            // 4. Verify only ONE additional session was created (total 2)
            assertEquals(2, fakeWrapper.createdSessions)
            assertTrue(session1.isClosed)
        }

    @Test
    fun `continues operation on RESULT_INTERNAL_ERROR`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())

            // 1. Initialize
            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            // 2. Setup put to fail with INTERNAL_ERROR
            fakeWrapper.lastSession!!.putDocumentsResultCode = AppSearchResult.RESULT_INTERNAL_ERROR

            // 3. Emit next snapshot
            flow.value = createTestSnapshot(101)
            testScheduler.advanceUntilIdle()

            // 4. Verify status is Error but session is still there (will try next time)
            assertTrue(manager.status.value is TrackingStatus.Error)
            assertEquals(1, fakeWrapper.createdSessions)

            // 5. Setup success and emit again
            fakeWrapper.lastSession!!.putDocumentsResultCode = AppSearchResult.RESULT_OK
            flow.value = createTestSnapshot(102)
            testScheduler.advanceUntilIdle()

            // 6. Verify recovery to Ready
            assertTrue(manager.status.value is TrackingStatus.Ready)
        }
}
