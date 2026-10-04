package com.example.minutewatcher.feature.semantic.search

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchManagerInitTest : SemanticSearchManagerTestFixtures() {
    @Test
    fun `successful initialization sets status to Ready`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())

            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            assertTrue(
                "Status was ${manager.status.value}",
                manager.status.value is TrackingStatus.Ready,
            )
            assertEquals(1, fakeWrapper.createdSessions)
            assertNotNull(fakeWrapper.lastSession)
            assertTrue(fakeWrapper.lastSession!!.schemaSet)
        }

    @Test
    fun `schema mismatch triggers force override`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            fakeWrapper.failNextWithInvalidSchema = true

            val flow = MutableStateFlow(createSnapshotWithOneSim())
            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            assertTrue(manager.status.value is TrackingStatus.Ready)
            assertEquals(2, fakeWrapper.createdSessions)
            assertTrue(fakeWrapper.lastSession!!.lastSchemaRequest!!.isForceOverride)
        }

    @Test
    fun `fatal error during init sets status to Error`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            fakeWrapper.failNextWithFatalError = true

            val flow = MutableStateFlow(createSnapshotWithOneSim())
            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            val status = manager.status.value
            assertTrue(
                "Expected Error but was $status",
                status is TrackingStatus.Error,
            )
            // Now mapped to NOT_SUPPORTED to avoid UI banners for system-level internal errors
            assertEquals(TrackingStatus.ErrorType.NOT_SUPPORTED, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `initialization with INTERNAL_ERROR sets NOT_SUPPORTED status`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            fakeWrapper.failNextWithFatalError = true

            val flow = MutableStateFlow(createSnapshotWithOneSim())
            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            val status = manager.status.value
            assertTrue("Expected Error status, but was $status", status is TrackingStatus.Error)
            assertEquals(
                TrackingStatus.ErrorType.NOT_SUPPORTED,
                (status as TrackingStatus.Error).type,
            )
        }

    @Test
    fun `initialization with IllegalStateException sets DATABASE_ERROR status regardless of message text`() =
        runTest(testDispatcher) {
            val messages = listOf("AppSearch is not supported on this device", "Something went wrong")
            for (msg in messages) {
                val manager = createManager(appScope)
                fakeWrapper.failSessionCreationWith = IllegalStateException(msg)

                val flow = MutableStateFlow(createSnapshotWithOneSim())
                manager.initialize(flow)
                testScheduler.advanceUntilIdle()

                val status = manager.status.value
                assertTrue("Expected Error status for '$msg', but was $status", status is TrackingStatus.Error)
                assertEquals(
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    (status as TrackingStatus.Error).type,
                )
            }
        }

    @Test
    fun `initialization on first run saves current package types`() =
        runTest(testDispatcher) {
            val manager = createManager(appScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())

            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            val savedTypes = dataStore.data.first()[stringSetPreferencesKey("appsearch_package_types")]
            val currentTypes = PackageType.entries.map { it.name }.toSet()
            assertEquals(currentTypes, savedTypes)
        }

    @Test
    fun `initialization with removed package type triggers cleanup`() =
        runTest(testDispatcher) {
            // 1. Pre-save a type that doesn't exist anymore
            dataStore.edit { prefs ->
                prefs[stringSetPreferencesKey("appsearch_package_types")] = setOf("OLD_TYPE", "MINUTES")
            }

            val manager = createManager(appScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())

            // 2. Initialize
            manager.initialize(flow)

            // 3. Verify removeByDocumentId was called for OLD_TYPE
            val session = fakeWrapper.lastSession!!
            val removeRequests = session.removeRequests
            assertTrue("Should have remove requests", removeRequests.isNotEmpty())

            val removedIds = removeRequests.flatMap { it.ids }
            assertTrue("Should remove OLD_TYPE docs", removedIds.any { it.contains("old_type") })

            // 4. Verify dataStore updated
            val savedTypes = dataStore.data.first()[stringSetPreferencesKey("appsearch_package_types")]
            val currentTypes = PackageType.entries.map { it.name }.toSet()
            assertEquals(currentTypes, savedTypes)
        }
}
