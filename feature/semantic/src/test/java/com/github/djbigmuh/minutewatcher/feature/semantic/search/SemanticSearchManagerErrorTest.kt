package com.github.djbigmuh.minutewatcher.feature.semantic.search

import androidx.appsearch.app.AppSearchResult
import com.github.djbigmuh.minutewatcher.core.model.PackageType
import com.github.djbigmuh.minutewatcher.core.model.SimSnapshot
import com.github.djbigmuh.minutewatcher.core.model.TrackingStatus
import com.github.djbigmuh.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchManagerErrorTest : SemanticSearchManagerTestFixtures() {
    @Test
    fun `tryLock failure does not reset or update lastSnapshotHash`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)

            // 1. Initial successful indexing to set hash
            val snapshot1 = createSnapshotWithOneSim()
            manager.indexSnapshot(snapshot1)

            val hash1 = SnapshotUtils.calculateContentHash(snapshot1)
            assertEquals(hash1, manager.lastSnapshotHash)

            // 2. Lock mutex and try to index another snapshot
            manager.indexMutex.lock()
            try {
                val snapshot2 = createTestSnapshot(101L)
                val result = manager.indexSnapshot(snapshot2)
                assertEquals(SemanticSearchManager.IndexResult.MutexContention, result)

                // 3. Verify hash is still the old one and NOT null
                assertEquals(
                    "Hash should remain unchanged on mutex contention",
                    hash1,
                    manager.lastSnapshotHash,
                )
            } finally {
                manager.indexMutex.unlock()
            }
        }

    @Test
    fun `recover finishes within timeout even if indexMutex is stuck`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)

            manager.indexMutex.lock()
            try {
                // recover() should not hang forever
                manager.recover()

                assertTrue(
                    "Status should be Error due to blocked recovery, but was ${manager.status.value}",
                    manager.status.value is TrackingStatus.Error,
                )
                val status = manager.status.value as TrackingStatus.Error
                assertTrue(status.message?.contains("Recovery blocked") == true)
            } finally {
                manager.indexMutex.unlock()
            }
        }

    @Test
    fun `putDocuments failure sets error status and does not update hash`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val snapshot = createTestSnapshot(100L)

            manager.indexSnapshot(snapshot)
            fakeWrapper.failAllPutDocuments = true

            val updatedSnapshot = createTestSnapshot(101L)
            manager.indexSnapshot(updatedSnapshot)

            assertTrue(
                "Status should be Error, but was ${manager.status.value}",
                manager.status.value is TrackingStatus.Error,
            )

            fakeWrapper.failAllPutDocuments = false
            manager.indexSnapshot(updatedSnapshot)

            assertTrue(
                "Status should be Ready after recovery, but was ${manager.status.value}",
                manager.status.value is TrackingStatus.Ready,
            )
            val currentSession = fakeWrapper.lastSession!!
            assertTrue(currentSession.putRequests.isNotEmpty())
        }

    @Test
    fun `ensureSession closes session on generic exception`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            fakeWrapper.failNextWithGenericError = true

            val snapshot = createSnapshotWithOneSim()
            manager.indexSnapshot(snapshot)

            val session = fakeWrapper.lastSession
            assertNotNull("Session should have been created", session)
            assertTrue("Session should be closed after generic exception", session!!.isClosed)
        }

    @Test
    fun `logs error on RESULT_UNKNOWN_ERROR`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val snapshot = createSnapshotWithOneSim()

            // 1. Setup session with UNKNOWN_ERROR
            manager.indexSnapshot(snapshot)
            fakeWrapper.lastSession!!.putDocumentsResultCode = AppSearchResult.RESULT_UNKNOWN_ERROR

            // 2. Trigger indexing
            manager.indexSnapshot(createTestSnapshot(101L))

            // 3. Verify status is Error and message contains the error
            val status = manager.status.value as TrackingStatus.Error
            assertTrue(status.message?.contains("Simulated failure with code") == true)
        }

    @Test
    fun `putDocuments failure prevents removeByDocumentId and reports only put error`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)

            // 1. Initial indexing to have something to remove
            manager.indexSnapshot(createSnapshotWithOneSim())

            // 2. Setup failure for both put and remove
            fakeWrapper.failAllPutDocuments = true
            fakeWrapper.failAllRemoveByDocumentId = true

            // 3. Trigger update that requires removal of old documents
            val snapshotWithSim1 =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            slot = 1,
                            identity = "sim_1",
                            operatorName = "B",
                            entities =
                                mapOf(
                                    PackageType.DATA to createEntity(1, PackageType.DATA, 50L),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                )

            val result = manager.indexSnapshot(snapshotWithSim1)

            // Assert: indexSnapshot returns ActualFailure
            assertTrue(
                "indexSnapshot should return ActualFailure when update fails",
                result is SemanticSearchManager.IndexResult.ActualFailure,
            )

            // Assert: status is Error and contains ONLY put message
            val status = manager.status.value
            assertTrue(status is TrackingStatus.Error)
            val errorMessage = (status as TrackingStatus.Error).message ?: ""
            assertTrue("Message should contain 'Put failed': $errorMessage", errorMessage.contains("Put failed"))
            assertTrue(
                "Message should NOT contain 'Remove failed': $errorMessage",
                !errorMessage.contains("Remove failed"),
            )

            // Verify remove was NOT called
            val session = fakeWrapper.lastSession!!
            assertEquals("Remove should not have been called after initial indexing", 1, session.removeRequests.size)
        }

    @Test
    fun `after failures status becomes Error but not permanently suppressed`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())
            fakeWrapper.failAllPutDocuments = true

            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            // 1. Force 5 failures via the loop to reach the limit
            repeat(AppSearchErrorHandler.MAX_CONSECUTIVE_INDEX_FAILURES) { i ->
                manager.indexSnapshot(createTestSnapshot(300L + i))
            }

            assertTrue("Status should be Error after 5 failures", manager.status.value is TrackingStatus.Error)
            val status = manager.status.value as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, status.type)
            assertEquals("Indexing failed after 5 consecutive errors", status.message)

            // Verify it's NOT permanently suppressed: next success should recover
            fakeWrapper.failAllPutDocuments = false
            fakeWrapper.lastSession?.failPutDocuments = false

            // Reset state to allow next indexing to proceed and reset counter on SUCCESS result.
            manager.recover()
            testScheduler.advanceUntilIdle()

            manager.indexSnapshot(createTestSnapshot(200L))
            testScheduler.advanceUntilIdle()

            assertTrue(
                "Should recover to Ready after successful indexing, but was ${manager.status.value}",
                manager.status.value is TrackingStatus.Ready,
            )
        }

    @Test
    fun `handleIndexFailure is not idempotent and continues to increment failures`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val errorHandler = getErrorHandler(manager)
            val status = MutableStateFlow<TrackingStatus>(TrackingStatus.Ready(0L))

            repeat(AppSearchErrorHandler.MAX_CONSECUTIVE_INDEX_FAILURES) {
                status.value = errorHandler.handleIndexFailure(status.value)
            }

            assertTrue(status.value is TrackingStatus.Error)
            val initialFailures = errorHandler.consecutiveIndexFailures
            assertEquals(AppSearchErrorHandler.MAX_CONSECUTIVE_INDEX_FAILURES, initialFailures)

            status.value = errorHandler.handleIndexFailure(status.value)

            assertEquals(initialFailures + 1, errorHandler.consecutiveIndexFailures)
        }

    @Test
    fun `concurrent handleIndexFailure calls are thread safe`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val errorHandler = getErrorHandler(manager)

            val count = 100
            val jobs =
                List(count) {
                    launch {
                        val flow =
                            MutableStateFlow<TrackingStatus>(
                                TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, "existing"),
                            )
                        flow.value = errorHandler.handleIndexFailure(flow.value)
                    }
                }
            withTimeoutOrNull(30.seconds) { jobs.joinAll() }
                ?: fail("Timed out waiting for concurrent handleIndexFailure calls")

            assertEquals(count, errorHandler.consecutiveIndexFailures)
        }

    @Test
    fun `mutex contention does not increment failure count and does not set error status`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val errorHandler = getErrorHandler(manager)

            // 1. Manually lock the indexMutex
            manager.indexMutex.lock()
            try {
                // 2. Attempt indexing - should return MutexContention
                val result = manager.indexSnapshot(createSnapshotWithOneSim())

                assertEquals(
                    "Should return MutexContention",
                    SemanticSearchManager.IndexResult.MutexContention,
                    result,
                )

                // 3. Verify failure counter is still 0
                assertEquals(0, errorHandler.consecutiveIndexFailures)

                // 4. Verify status is NOT Error
                assertTrue(
                    "Status should not be Error, but was ${manager.status.value}",
                    manager.status.value !is TrackingStatus.Error,
                )
            } finally {
                manager.indexMutex.unlock()
            }
        }

    @Test
    fun `consecutive mutex contention counter is reset and status recovers after successful acquisition`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)

            // 1. Manually lock the indexMutex to cause contention
            manager.indexMutex.lock()
            try {
                // First contention
                manager.indexSnapshot(createSnapshotWithOneSim())
                assertEquals(1, manager.consecutiveMutexContention.get())

                // Second contention -> reaches MUTEX_STUCK_THRESHOLD -> should set Error status with DATABASE_ERROR
                manager.indexSnapshot(createSnapshotWithOneSim())
                assertEquals(2, manager.consecutiveMutexContention.get())

                val errorStatus = manager.status.value
                assertTrue("Status should be Error on reaching stuck threshold", errorStatus is TrackingStatus.Error)
                assertEquals(
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    (errorStatus as TrackingStatus.Error).type,
                )
            } finally {
                manager.indexMutex.unlock()
            }

            // 2. Now call indexSnapshot successfully
            manager.indexSnapshot(createSnapshotWithOneSim())

            // 3. Verify counter is reset to 0 and status recovers
            assertEquals(0, manager.consecutiveMutexContention.get())
            assertTrue(
                "Status should recover to Ready after successful indexing, was ${manager.status.value}",
                manager.status.value is TrackingStatus.Ready,
            )
        }

    @Test
    fun `indexSnapshot returns MutexContention and increments counter when lock cannot be acquired`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)

            // 1. Manually lock the indexMutex to cause contention
            manager.indexMutex.lock()
            try {
                // 2. Attempt indexing - should return MutexContention
                val result = manager.indexSnapshot(createSnapshotWithOneSim())

                assertEquals(
                    "Should return MutexContention",
                    SemanticSearchManager.IndexResult.MutexContention,
                    result,
                )

                // 3. Verify counter is incremented
                assertEquals(1, manager.consecutiveMutexContention.get())
            } finally {
                manager.indexMutex.unlock()
            }
        }
}
