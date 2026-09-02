package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
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
        runTest {
            val manager = createManager(this)
            fakeWrapper.failAllPutDocuments = true
            val snapshot = createSnapshotWithOneSim()
            val flow = MutableStateFlow(snapshot)

            manager.initialize(flow)

            // Wait for Error status
            val errorStatus = manager.status.filterIsInstance<TrackingStatus.Error>().first()
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, errorStatus.type)
        }

    @Test
    fun `removeDocuments failure transitions to Error state`() =
        runTest {
            val manager = createManager(this)
            fakeWrapper.failAllRemoveByDocumentId = true
            val snapshot = createSnapshotWithOneSim()
            val flow = MutableStateFlow(snapshot)

            manager.initialize(flow)

            // Wait for Error status
            val errorStatus = manager.status.filterIsInstance<TrackingStatus.Error>().first()
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, errorStatus.type)
        }

    @Test
    fun `schema update failure transitions to Error state`() =
        runTest {
            val manager = createManager(this)
            fakeWrapper.failNextWithFatalError = true
            val snapshot = createSnapshotWithOneSim()
            val flow = MutableStateFlow(snapshot)

            manager.initialize(flow)

            // Wait for Error status
            val errorStatus = manager.status.filterIsInstance<TrackingStatus.Error>().first()
            assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, errorStatus.type)
        }

    @Test
    fun `successful index after error restores Ready status`() =
        runTest {
            val manager = createManager(this)
            fakeWrapper.failAllPutDocuments = true
            val snapshot = createSnapshotWithOneSim()
            val flow = MutableStateFlow(snapshot)

            manager.initialize(flow)

            // Wait for Error status
            manager.status.filterIsInstance<TrackingStatus.Error>().first()

            // Fix the failure
            fakeWrapper.failAllPutDocuments = false
            // Update snapshot to trigger new indexing
            currentTime += 1000
            flow.value = createTestSnapshot(50)

            // Wait for Ready status
            val readyStatus = manager.status.filterIsInstance<TrackingStatus.Ready>().first()
            assertEquals(currentTime, readyStatus.lastUpdateTimestamp)
        }

    @Test
    fun `query during indexing or error returns controlled failure result`() =
        runTest {
            val manager = createManager(this)
            fakeWrapper.failSessionCreationWith = AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR)

            val result = manager.query("test")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is AppSearchException)
        }

    @Test
    fun `concurrent index requests do not break state machine`() =
        runTest {
            val manager = createManager(this)
            val snapshot = createSnapshotWithOneSim()
            val flow = MutableStateFlow(snapshot)

            manager.initialize(flow)

            // Trigger multiple updates rapidly
            repeat(5) {
                currentTime += 100
                flow.value = createTestSnapshot(it.toLong())
            }

            // Wait for Ready status for the last one
            val readyStatus = manager.status.filterIsInstance<TrackingStatus.Ready>().first()
            assertEquals(currentTime, readyStatus.lastUpdateTimestamp)
        }
}
