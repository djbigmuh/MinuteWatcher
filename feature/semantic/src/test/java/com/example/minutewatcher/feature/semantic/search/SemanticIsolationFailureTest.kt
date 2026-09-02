package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SemanticIsolationFailureTest : SemanticSearchManagerTestFixtures() {
    @Test
    fun `putDocuments failure transitions to Error state and clears Indexing`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failAllPutDocuments = true
            val snapshot = createTestSnapshot(100)
            val flow = MutableStateFlow(snapshot)

            manager.initialize(flow)

            // Wait for Error status
            val errorStatus = manager.status.first { it is TrackingStatus.Error } as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, errorStatus.type)
        }

    @Test
    fun `removeDocuments failure transitions to Error state`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failAllRemoveByDocumentId = true
            val snapshot = createTestSnapshot(100)
            val flow = MutableStateFlow(snapshot)

            manager.initialize(flow)

            // Wait for Error status
            val errorStatus = manager.status.first { it is TrackingStatus.Error } as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, errorStatus.type)
        }

    @Test
    fun `schema update failure transitions to Error state`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failNextWithFatalError = true
            val snapshot = createTestSnapshot(100)
            val flow = MutableStateFlow(snapshot)

            manager.initialize(flow)

            // Wait for Error status
            val errorStatus = manager.status.first { it is TrackingStatus.Error } as TrackingStatus.Error
            assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, errorStatus.type)
        }

    @Test
    fun `successful index after error restores Ready status`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failAllPutDocuments = true
            val flow = MutableStateFlow(createTestSnapshot(100))

            manager.initialize(flow)

            // Wait for initial Error
            testScheduler.advanceUntilIdle()
            assertTrue("Should be Error, but was ${manager.status.value}", manager.status.value is TrackingStatus.Error)

            // Fix the failure
            fakeWrapper.failAllPutDocuments = false
            fakeWrapper.lastSession?.failPutDocuments = false

            // Update currentTime and snapshot to trigger new indexing
            currentTime += 1000
            flow.value = createTestSnapshot(50)

            // Wait for Ready status
            testScheduler.advanceUntilIdle()

            val status = manager.status.value
            assertTrue("Status should be Ready, but was $status", status is TrackingStatus.Ready)
            val readyStatus = status as TrackingStatus.Ready
            assertEquals(currentTime, readyStatus.lastUpdateTimestamp)
        }

    @Test
    fun `query during indexing or error returns controlled failure result`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failSessionCreationWith = AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR)

            val result = manager.query("test")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AppSearchException)
        }

    @Test
    fun `concurrent index requests do not break state machine`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val flow = MutableStateFlow(createTestSnapshot(100))

            manager.initialize(flow)

            // Trigger multiple updates rapidly
            repeat(5) {
                currentTime += 100
                flow.value = createTestSnapshot(it.toLong())
            }

            // Wait for Ready status for the last one
            val readyStatus =
                manager.status.first {
                    it is TrackingStatus.Ready && it.lastUpdateTimestamp == currentTime
                } as TrackingStatus.Ready
            assertEquals(currentTime, readyStatus.lastUpdateTimestamp)
        }
}
