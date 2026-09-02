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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchManagerErrorTest : SemanticSearchManagerTestFixtures() {
    @Test
    fun `putDocuments failure sets error status and does not update hash`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot = createTestSnapshot(100)

            manager.indexSnapshot(snapshot)
            fakeWrapper.failAllPutDocuments = true

            val updatedSnapshot = createTestSnapshot(101)
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
            val manager = createManager(backgroundScope)
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
            val manager = createManager(backgroundScope)
            val snapshot = createSnapshotWithOneSim()

            // 1. Setup session with UNKNOWN_ERROR
            manager.indexSnapshot(snapshot)
            fakeWrapper.lastSession!!.putDocumentsResultCode = AppSearchResult.RESULT_UNKNOWN_ERROR

            // 2. Trigger indexing
            manager.indexSnapshot(createTestSnapshot(101))

            // 3. Verify status is Error and message contains the error
            val status = manager.status.value as TrackingStatus.Error
            assertTrue(status.message?.contains("Simulated failure with code") == true)
        }

    @Test
    fun `putDocuments failure prevents removeByDocumentId and reports only put error`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)

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
                            isActive = true,
                            entities =
                                mapOf(
                                    PackageType.DATA to createEntity(1, PackageType.DATA, 50),
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
            // Note: throwIfErrors throws putEx directly if it is an AppSearchException and removeEx is null
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
            val manager = createManager(backgroundScope)
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

            manager.indexSnapshot(createTestSnapshot(200))
            testScheduler.advanceUntilIdle()

            assertTrue(
                "Should recover to Ready after successful indexing, but was ${manager.status.value}",
                manager.status.value is TrackingStatus.Ready,
            )
        }

    @Test
    fun `handleIndexFailure is not idempotent and continues to increment failures`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
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
            val manager = createManager(backgroundScope)
            val errorHandler = getErrorHandler(manager)

            val count = 100
            val jobs =
                List(count) {
                    launch {
                        val flow = MutableStateFlow<TrackingStatus>(TrackingStatus.Ready(0L))
                        flow.value = errorHandler.handleIndexFailure(flow.value)
                    }
                }
            jobs.joinAll()

            assertEquals(count, errorHandler.consecutiveIndexFailures)
        }

    @Test
    fun `mutex timeout does not increment failure count and does not set error status`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val errorHandler = getErrorHandler(manager)

            // 1. Manually lock the indexMutex to cause timeout
            val indexMutexField = SemanticSearchManager::class.java.getDeclaredField("indexMutex")
            indexMutexField.isAccessible = true
            val indexMutex = indexMutexField.get(manager) as kotlinx.coroutines.sync.Mutex

            indexMutex.lock()
            try {
                // 2. Attempt indexing - should timeout based on SHORT_TIMEOUT_MS
                val result = manager.indexSnapshot(createSnapshotWithOneSim())

                assertEquals(
                    "Should return MutexTimeout",
                    SemanticSearchManager.IndexResult.MutexTimeout,
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
                indexMutex.unlock()
            }
        }
}
