package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.exceptions.AppSearchException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edge case tests verify behavior under error conditions.
 * These are critical for production stability on custom ROMs
 * where system services may degrade or fail unexpectedly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchManagerTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val context: Context = mock()
    private val timeProvider: TimeProvider = mock()
    private var currentTime = 1000000L
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var fakeWrapper: FakeAppSearchWrapper
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    @Before
    fun setup() {
        whenever(timeProvider.now()).thenAnswer { currentTime }
        whenever(timeProvider.elapsedRealtime()).thenAnswer { currentTime }
        whenever(timeProvider.uptimeMillis()).thenAnswer { currentTime }

        fakeWrapper = FakeAppSearchWrapper()
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(testDispatcher),
                produceFile = { tmpFolder.newFile("test_app_settings.preferences_pb") },
            )
    }

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
    fun `indexSnapshot works with slot 1`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            slot = 1,
                            identity = "sim_1",
                            operator = "B",
                            isActive = true,
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
    fun `empty snapshot triggers stale document removal`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)

            // 1. Index snapshot with one SIM
            manager.indexSnapshot(createSnapshotWithOneSim())
            val session = fakeWrapper.lastSession!!
            val initialRemoves = session.removeRequests.size

            // 2. Pass empty snapshot
            val emptySnapshot = createSnapshot(emptyList())
            val result = manager.indexSnapshot(emptySnapshot)

            assertTrue("indexSnapshot should return true for empty snapshot", result)
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
    fun `indexSnapshot puts new documents before removing stale ones`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot = createSnapshotWithOneSim()

            // Setup failure for putDocuments
            fakeWrapper.failAllPutDocuments = true

            // Act: try indexing
            manager.indexSnapshot(snapshot)

            // Assert: verify put was attempted but failed, and remove WAS called now
            // (due to new logic where we continue to removal even if put fails)
            val session = fakeWrapper.lastSession!!
            assertTrue(
                "putDocuments should have failed (no successful requests recorded)",
                session.putRequests.isEmpty(),
            )
            assertEquals(
                "removeByDocumentId SHOULD have been called",
                1,
                session.removeRequests.size,
            )
        }

    @Test
    fun `stale documents are removed even if putDocuments fails`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)

            // 1. Initial indexing
            manager.indexSnapshot(createSnapshotWithOneSim())
            val session = fakeWrapper.lastSession!!

            // 2. Setup failure for putDocuments
            fakeWrapper.failAllPutDocuments = true

            // 3. Emit empty snapshot (which will trigger removal of sim_0 docs)
            // Wait, an empty snapshot results in `documents` being empty in performIndexUpdate.
            // If `documents` is empty, `putDocuments` is not called.
            // I need a snapshot that HAS some documents but also requires removal of others.

            // Let's create a snapshot with SIM 1, while SIM 0 was previously indexed.
            val snapshotWithSim1 =
                createSnapshot(
                    listOf(
                        SimSnapshot(
                            slot = 1,
                            identity = "sim_1",
                            operator = "B",
                            isActive = true,
                            entities =
                                mapOf(
                                    PackageType.DATA to createEntity(1, PackageType.DATA, 50),
                                ),
                            autoResetDay = 1,
                        ),
                    ),
                )

            manager.indexSnapshot(snapshotWithSim1)

            // 4. Assert: verify put was attempted but failed, and remove WAS called
            // Total removeByDocumentId calls should be 2:
            // 1st: initial index of SIM 0 (removes slot 1)
            // 2nd: index of SIM 1 (removes slot 0)
            assertEquals(
                "Total removeByDocumentId calls should be 2",
                2,
                session.removeRequests.size,
            )

            val lastRemove = session.removeRequests.last()
            assertTrue("Should remove sim_0_minutes", lastRemove.ids.contains("sim_0_minutes"))
            assertTrue("Should remove sim_0_data", lastRemove.ids.contains("sim_0_data"))

            // Verify status is Error because put failed
            assertTrue(manager.status.value is TrackingStatus.Error)
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

            // 6. Verify recovery to Active
            assertTrue(manager.status.value is TrackingStatus.Active)
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
                            operator = "B",
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
            val failureField = SemanticSearchManager::class.java.getDeclaredField("consecutiveIndexFailures")
            failureField.isAccessible = true
            val failures = (failureField.get(manager) as java.util.concurrent.atomic.AtomicInteger).get()

            val timeField = SemanticSearchManager::class.java.getDeclaredField("lastIndexFailureTime")
            timeField.isAccessible = true
            val lastTime = (timeField.get(manager) as java.util.concurrent.atomic.AtomicLong).get()

            assertEquals(0, failures)
            assertEquals(0L, lastTime)
        }

    private fun createManager(scope: CoroutineScope) =
        SemanticSearchManager(
            context = context,
            scope = scope,
            appSearchWrapper = fakeWrapper,
            ioDispatcher = testDispatcher,
            dataStore = dataStore,
            timeProvider = timeProvider,
        )

    private fun createEntity(
        slot: Int,
        type: PackageType,
        remaining: Long,
    ) = SemanticEntity(
        id = "sim_${slot}_${type.name}",
        simSlot = slot,
        operatorName = "ABC",
        packageType = type,
        limit = 10000,
        used = 10000 - remaining,
        remaining = remaining,
        unit = if (type == PackageType.MINUTES) MeasurementUnit.MINUTES else MeasurementUnit.BYTES,
        periodStart = 0,
        periodEnd = 0,
        lastUpdateTime = currentTime,
        sourceType = DataSourceType.SYSTEM,
    )

    private fun createSnapshot(sims: List<SimSnapshot>) =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = currentTime,
            simSnapshots = sims,
            capabilities = emptyList(),
            isManualUpdate = true,
        )

    private fun createTestSnapshot(remaining: Long) =
        createSnapshot(
            listOf(
                SimSnapshot(
                    slot = 0,
                    identity = "sim_0",
                    operator = "A",
                    isActive = true,
                    entities =
                        mapOf(
                            PackageType.MINUTES to
                                createEntity(
                                    0,
                                    PackageType.MINUTES,
                                    remaining,
                                ),
                        ),
                    autoResetDay = 1,
                ),
            ),
        )

    private fun createSnapshotWithOneSim() = createTestSnapshot(100)

    private class FakeAppSearchWrapper : AppSearchWrapper {
        var createdSessions = 0
        var lastSession: FakeAppSearchSessionWrapper? = null
        var failNextWithInvalidSchema = false
        var failNextWithFatalError = false
        var failNextWithGenericError = false
        var failAllPutDocuments = false
        var failAllRemoveByDocumentId = false
        var failSessionCreationWith: Exception? = null

        override suspend fun createSearchSession(
            context: Context,
            databaseName: String,
        ): AppSearchSessionWrapper {
            failSessionCreationWith?.let { throw it }
            createdSessions++
            val session = FakeAppSearchSessionWrapper(this)
            session.failPutDocuments = failAllPutDocuments
            session.failRemoveByDocumentId = failAllRemoveByDocumentId
            lastSession = session
            return session
        }

        override fun createSchemaRequest(forceOverride: Boolean): SetSchemaRequest {
            val mockRequest: SetSchemaRequest = mock()
            whenever(mockRequest.isForceOverride).thenReturn(forceOverride)
            return mockRequest
        }

        override fun createPutRequest(documents: List<UsageDocument>): PutDocumentsRequest {
            val builder = PutDocumentsRequest.Builder()
            documents.forEach { doc ->
                val genericDoc =
                    androidx.appsearch.app.GenericDocument
                        .Builder<androidx.appsearch.app.GenericDocument.Builder<*>>(
                            doc.namespace,
                            doc.id,
                            "UsageDocument",
                        ).build()
                builder.addGenericDocuments(genericDoc)
            }
            return builder.build()
        }

        override fun createRemoveRequest(ids: Set<String>): RemoveByDocumentIdRequest =
            RemoveByDocumentIdRequest.Builder("usage").addIds(ids).build()
    }

    private class FakeAppSearchSessionWrapper(
        private val wrapper: FakeAppSearchWrapper,
    ) : AppSearchSessionWrapper {
        override var isClosed = false
            private set

        var schemaSet = false
        var lastSchemaRequest: SetSchemaRequest? = null
        val putRequests = mutableListOf<PutDocumentsRequest>()
        val removeRequests = mutableListOf<RemoveByDocumentIdRequest>()
        var failPutDocuments = false
        var failRemoveByDocumentId = false
        var putDocumentsResultCode: Int = AppSearchResult.RESULT_OK

        override suspend fun setSchema(request: SetSchemaRequest) {
            if (wrapper.failNextWithInvalidSchema && !request.isForceOverride) {
                throw AppSearchException(AppSearchResult.RESULT_INVALID_SCHEMA)
            }
            if (wrapper.failNextWithFatalError) {
                throw AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR)
            }
            if (wrapper.failNextWithGenericError) {
                @Suppress("TooGenericExceptionThrown")
                throw RuntimeException("Generic error")
            }
            schemaSet = true
            lastSchemaRequest = request
        }

        override suspend fun putDocuments(request: PutDocumentsRequest) {
            if (failPutDocuments || wrapper.failAllPutDocuments) {
                throw AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR, "Put failed")
            }
            if (putDocumentsResultCode != AppSearchResult.RESULT_OK) {
                throw AppSearchException(putDocumentsResultCode, "Simulated failure with code $putDocumentsResultCode")
            }
            putRequests.add(request)
        }

        override suspend fun removeByDocumentId(request: RemoveByDocumentIdRequest) {
            if (failRemoveByDocumentId || wrapper.failAllRemoveByDocumentId) {
                throw AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR, "Remove failed")
            }
            removeRequests.add(request)
        }

        override fun close() {
            isClosed = true
        }
    }
}
