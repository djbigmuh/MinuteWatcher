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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchManagerInitTest : SemanticSearchManagerTestFixtures() {
    @Test
    fun `successful initialization sets status to Active`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())

            manager.initialize(flow)

            assertTrue(
                "Status was ${manager.status.value}",
                manager.status.value is TrackingStatus.Active,
            )
            assertEquals(1, fakeWrapper.createdSessions)
            assertNotNull(fakeWrapper.lastSession)
            assertTrue(fakeWrapper.lastSession!!.schemaSet)
        }

    @Test
    fun `schema mismatch triggers force override`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failNextWithInvalidSchema = true

            val flow = MutableStateFlow(createSnapshotWithOneSim())
            manager.initialize(flow)
            testScheduler.advanceUntilIdle()

            assertTrue(manager.status.value is TrackingStatus.Active)
            assertEquals(2, fakeWrapper.createdSessions)
            assertTrue(fakeWrapper.lastSession!!.lastSchemaRequest!!.isForceOverride)
        }

    @Test
    fun `fatal error during init sets status to Error`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failNextWithFatalError = true

            val flow = MutableStateFlow(createSnapshotWithOneSim())
            manager.initialize(flow)

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
            val manager = createManager(backgroundScope)
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
    fun `initialization with IllegalStateException sets NOT_SUPPORTED status`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failSessionCreationWith = IllegalStateException("Service not found")

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
    fun `initialization on first run saves current package types`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val flow = MutableStateFlow(createSnapshotWithOneSim())

            manager.initialize(flow)

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

            val manager = createManager(backgroundScope)
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
