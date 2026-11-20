package com.github.djbigmuh.minutewatcher.feature.semantic.search

import androidx.appsearch.app.AppSearchResult
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchManagerRecoveryTest : SemanticSearchManagerTestFixtures() {
    @Test
    fun `recover after error reinitializes session`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val snapshot = createSnapshotWithOneSim()

            // 1. Initial indexing
            manager.indexSnapshot(snapshot)
            assertEquals(1, fakeWrapper.createdSessions)
            val session1 = fakeWrapper.lastSession!!

            // 2. Simulate failure that leads to retry (Schema Mismatch)
            session1.putDocumentsResultCode = AppSearchResult.RESULT_INVALID_SCHEMA
            val updatedSnapshot = createTestSnapshot(99)

            manager.indexSnapshot(updatedSnapshot)

            // Should have retried and created a new session
            assertEquals(2, fakeWrapper.createdSessions)
            assertTrue(session1.isClosed)
            assertTrue(manager.status.value is TrackingStatus.Ready)
        }

    @Test
    fun `recreates session on RESULT_INVALID_SCHEMA`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val snapshot = createSnapshotWithOneSim()

            // 1. Initial indexing
            manager.indexSnapshot(snapshot)
            val session1 = fakeWrapper.lastSession!!

            // 2. Setup next put to fail with INVALID_SCHEMA
            session1.putDocumentsResultCode = AppSearchResult.RESULT_INVALID_SCHEMA

            // 3. Trigger next indexing
            val updatedSnapshot = createTestSnapshot(101)
            manager.indexSnapshot(updatedSnapshot)

            // 4. Verify session was recreated (total 2 created)
            assertEquals(2, fakeWrapper.createdSessions)
            assertTrue("Old session should be closed", session1.isClosed)
            assertTrue("New session should be Ready", manager.status.value is TrackingStatus.Ready)
        }

    @Test
    fun `recover resets failure counters`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)

            // 1. Force failures manually to reach the limit
            fakeWrapper.failAllPutDocuments = true
            repeat(AppSearchErrorHandler.MAX_CONSECUTIVE_INDEX_FAILURES) { i ->
                manager.indexSnapshot(createTestSnapshot(200L + i))
            }

            assertTrue("Status should be Error after 5 failures", manager.status.value is TrackingStatus.Error)
            val errorHandler = getErrorHandler(manager)
            assertTrue(
                "Counter should be >= 5, but was ${errorHandler.consecutiveIndexFailures}",
                errorHandler.consecutiveIndexFailures >= 5,
            )

            // 2. Call recover
            manager.recover()

            // 3. Verify counters via reflection
            assertEquals(0, errorHandler.consecutiveIndexFailures)
        }

    @Test
    fun `after recover counter is reset and next error starts from 1`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())
            fakeWrapper.failAllPutDocuments = true

            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            repeat(AppSearchErrorHandler.MAX_CONSECUTIVE_INDEX_FAILURES) { i ->
                flow.value = createTestSnapshot(100L + i)
                testScheduler.advanceUntilIdle()
            }

            // Disable failures so recover() results in a successful indexing and 0 counter.
            fakeWrapper.failAllPutDocuments = false
            fakeWrapper.lastSession?.failPutDocuments = false

            manager.recover()
            testScheduler.advanceUntilIdle()

            val errorHandler = getErrorHandler(manager)
            assertEquals(0, errorHandler.consecutiveIndexFailures)

            // Trigger one failure
            fakeWrapper.failAllPutDocuments = true
            manager.indexSnapshot(createTestSnapshot(200L))
            testScheduler.advanceUntilIdle()

            assertEquals(1, errorHandler.consecutiveIndexFailures)
        }

    @Test
    fun `concurrent initialize and recover does not crash and maintains consistency`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())

            // Start multiple concurrent operations
            val jobs =
                List(10) { i ->
                    launch {
                        if (i % 2 == 0) {
                            manager.initialize(flow)
                        } else {
                            manager.recover()
                        }
                    }
                }
            withTimeoutOrNull(30.seconds) { jobs.joinAll() }
                ?: fail("Timed out waiting for concurrent initialize and recover operations")
            testScheduler.advanceUntilIdle()

            assertTrue(manager.status.value is TrackingStatus.Ready)
            // Even with many calls, we should have at least one session created
            assertTrue(fakeWrapper.createdSessions >= 1)
        }

    @Test
    fun `recover followed by empty snapshot correctly triggers stale document removal`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)

            // 1. Index snapshot with one SIM
            manager.indexSnapshot(createSnapshotWithOneSim())
            val session = fakeWrapper.lastSession!!
            val initialRemoves = session.removeRequests.size

            // 2. Call recover (simulates error recovery which resets lastSnapshotHash)
            manager.recover()

            // 3. Pass empty snapshot (e.g. SIMs removed)
            val result = manager.indexSnapshot(createSnapshot(emptyList()))

            // 4. Verify that removeRequests was called to clean up sim_0 documents
            assertTrue(
                "indexSnapshot should return Success for empty snapshot after recover",
                result is SemanticSearchManager.IndexResult.Success,
            )
            assertEquals(
                "Should have one more remove request for stale data",
                initialRemoves + 1,
                session.removeRequests.size,
            )
        }
}
