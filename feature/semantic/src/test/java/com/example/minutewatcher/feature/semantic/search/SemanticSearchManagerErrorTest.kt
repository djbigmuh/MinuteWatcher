package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.app.AppSearchResult
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
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
                "Status should be Active after recovery, but was ${manager.status.value}",
                manager.status.value is TrackingStatus.Active,
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
    fun `both putDocuments and removeByDocumentId failure logs both messages`() =
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

            // Assert: indexSnapshot returns false
            assertTrue("indexSnapshot should return false when update fails", !result)

            // Assert: status is Error and contains both messages
            val status = manager.status.value
            assertTrue(status is TrackingStatus.Error)
            val errorMessage = (status as TrackingStatus.Error).message ?: ""
            assertTrue("Message should contain put failure: $errorMessage", errorMessage.contains("put failed"))
            assertTrue("Message should contain remove failure: $errorMessage", errorMessage.contains("remove failed"))
            assertTrue("Message should contain 'Put failed': $errorMessage", errorMessage.contains("Put failed"))
            assertTrue("Message should contain 'Remove failed': $errorMessage", errorMessage.contains("Remove failed"))
        }

    @Test
    fun `after MAX_CONSECUTIVE_INDEX_FAILURES consecutive failures status becomes suppressed Error`() =
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

            val status = manager.status.value as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, status.type)
            assertEquals("Indexing suppressed after 5 failures", status.message)
        }

    @Test
    fun `handleIndexFailure is idempotent when already suppressed`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val errorHandler = getErrorHandler(manager)
            val status = MutableStateFlow<TrackingStatus>(TrackingStatus.Active)

            repeat(AppSearchErrorHandler.MAX_CONSECUTIVE_INDEX_FAILURES) {
                errorHandler.handleIndexFailure(status)
            }

            val suppressedStatus = status.value
            assertTrue(suppressedStatus is TrackingStatus.Error)
            val initialFailures = errorHandler.consecutiveIndexFailures

            errorHandler.handleIndexFailure(status)

            assertEquals("Status should not change", suppressedStatus, status.value)
            assertEquals("Counter should not increment further", initialFailures, errorHandler.consecutiveIndexFailures)
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
                        errorHandler.handleIndexFailure(MutableStateFlow(TrackingStatus.Active))
                    }
                }
            jobs.joinAll()

            assertEquals(count, errorHandler.consecutiveIndexFailures)
        }
}
