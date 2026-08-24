package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.app.AppSearchResult
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
class SemanticSearchManagerRecoveryTest : SemanticSearchManagerTestFixtures() {

    @Test
    fun `recover after error reinitializes session`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
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
            assertTrue(manager.status.value is TrackingStatus.Active)
        }

    @Test
    fun `recreates session on RESULT_INVALID_SCHEMA`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
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
            assertTrue("New session should be active", manager.status.value is TrackingStatus.Active)
        }

    @Test
    fun `recover resets failure counters`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot = createSnapshotWithOneSim()

            // 1. Force 5 failures
            fakeWrapper.failNextWithGenericError = true
            repeat(5) {
                manager.indexSnapshot(snapshot)
            }

            assertTrue("Status should be Error after 5 failures", manager.status.value is TrackingStatus.Error)

            // 2. Call recover
            manager.recover()

            // 3. Verify counters via reflection
            val errorHandler = getErrorHandler(manager)
            assertEquals(0, errorHandler.consecutiveIndexFailures)
        }

    @Test
    fun `after recover counter is reset and next error starts from 1`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())
            fakeWrapper.failAllPutDocuments = true

            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            repeat(AppSearchErrorHandler.MAX_CONSECUTIVE_INDEX_FAILURES) {
                flow.value = createTestSnapshot(100L + it)
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
            flow.value = createTestSnapshot(200L)
            testScheduler.advanceUntilIdle()

            assertEquals(1, errorHandler.consecutiveIndexFailures)
        }

    @Test
    fun `concurrent initialize and recover does not crash and maintains consistency`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
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
            jobs.joinAll()

            assertTrue(manager.status.value is TrackingStatus.Active)
            // Even with many calls, we should have at least one session created
            assertTrue(fakeWrapper.createdSessions >= 1)
        }
}
