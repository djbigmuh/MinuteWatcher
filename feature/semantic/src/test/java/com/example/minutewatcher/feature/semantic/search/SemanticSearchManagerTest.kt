package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.common.util.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSearchManagerTest {
    private val context: Context = mock()
    private var currentTime = 1000000L
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var fakeWrapper: FakeAppSearchWrapper

    @Before
    fun setup() {
        fakeWrapper = FakeAppSearchWrapper()
    }

    private fun advanceTime(millis: Long) {
        currentTime += millis
    }

    @Test
    fun `successful initialization sets status to Active`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val flow = MutableStateFlow(createEmptySnapshot())
        
        manager.initialize(flow)

        assertTrue("Status was ${manager.status.value}", manager.status.value is TrackingStatus.Active)
        assertEquals(1, fakeWrapper.createdSessions)
        assertNotNull(fakeWrapper.lastSession)
        assertTrue(fakeWrapper.lastSession!!.schemaSet)
    }

    @Test
    fun `schema mismatch triggers force override`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        fakeWrapper.failNextWithInvalidSchema = true
        
        val flow = MutableStateFlow(createEmptySnapshot())
        manager.initialize(flow)

        assertTrue(manager.status.value is TrackingStatus.Active)
        assertEquals(2, fakeWrapper.createdSessions)
        assertTrue(fakeWrapper.lastSession!!.lastSchemaRequest!!.isForceOverride)
    }

    @Test
    fun `fatal error during init sets status to Error`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        fakeWrapper.failNextWithFatalError = true
        
        val flow = MutableStateFlow(createEmptySnapshot())
        manager.initialize(flow)

        val status = manager.status.value
        assertTrue("Expected Error but was $status", status is TrackingStatus.Error)
        assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)
    }

    @Test
    fun `indexSnapshot triggers session creation if needed`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val snapshot = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to createEntity(0, PackageType.MINUTES, 100)))))
        
        manager.indexSnapshot(snapshot)
        
        assertEquals(1, fakeWrapper.createdSessions)
        assertNotNull(fakeWrapper.lastSession)
    }

    @Test
    fun `recover reinitializes session`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        manager.initialize(MutableStateFlow(createEmptySnapshot()))
        
        assertEquals(1, fakeWrapper.createdSessions)
        manager.recover()
        assertEquals(2, fakeWrapper.createdSessions)
    }

    @Test
    fun `indexSnapshot eventually indexes delayed snapshot`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)

        // 1. First indexing (Manual, so no throttle)
        val s1 = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to createEntity(0, PackageType.MINUTES, 100)))), isManual = true)
        manager.indexSnapshot(s1)
        val session = fakeWrapper.lastSession!!
        assertEquals(1, session.putRequests.size)

        // 2. Second indexing (Within throttle interval)
        advanceTime(60_000) // 1 minute passed
        val s2 = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to createEntity(0, PackageType.MINUTES, 101)))), isManual = false)
        
        launch { manager.indexSnapshot(s2) }
        runCurrent()
        
        // Should NOT be indexed yet
        assertEquals("Should wait for throttle", 1, session.putRequests.size)

        // 3. Advance time to pass the 5-minute interval
        advanceTime(240_000) // 4 more minutes passed, total 5
        advanceTimeBy(300_000.milliseconds) // Advance virtual clock of dispatcher
        runCurrent()

        // Should BE indexed now
        assertEquals("Should be indexed after delay", 2, session.putRequests.size)
    }

    @Test
    fun `indexSnapshot hash check skips identical data`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)

        val s = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to createEntity(0, PackageType.MINUTES, 100)))), isManual = true)
        manager.indexSnapshot(s)
        val session = fakeWrapper.lastSession!!
        val initialPuts = session.putRequests.size

        manager.indexSnapshot(s)
        assertEquals("Should skip due to identical hash", initialPuts, session.putRequests.size)
    }

    @Test
    fun `indexSnapshot removes stale package type`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        
        val eMin = createEntity(0, PackageType.MINUTES, 100)
        val eData = createEntity(0, PackageType.DATA, 200)
        val snapshot1 = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(
            PackageType.MINUTES to eMin,
            PackageType.DATA to eData
        ))), isManual = true)
        manager.indexSnapshot(snapshot1)
        
        advanceTime(600_000)
        val snapshot2 = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to eMin))), isManual = true)
        manager.indexSnapshot(snapshot2)

        val lastRemove = fakeWrapper.lastSession!!.removeRequests.last()
        assertTrue(lastRemove.ids.contains("sim_0_DATA"))
        assertFalse(lastRemove.ids.contains("sim_0_MINUTES"))
    }

    @Test
    fun `indexSnapshot works with slot 1`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val snapshot = createSnapshot(listOf(SimSnapshot(1, "B", true, mapOf(PackageType.MINUTES to createEntity(1, PackageType.MINUTES, 50)))))
        manager.indexSnapshot(snapshot)
        
        val lastPut = fakeWrapper.lastSession!!.putRequests.last()
        assertTrue(lastPut.genericDocuments.isNotEmpty())
    }

    @Test
    fun `putDocuments failure sets error status and does not update hash`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val snapshot = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to createEntity(0, PackageType.MINUTES, 100)))))
        
        // 1. Setup session to fail on putDocuments
        manager.indexSnapshot(snapshot) // First call to initialize session
        val session = fakeWrapper.lastSession!!
        session.failPutDocuments = true

        // 2. Trigger another indexing (with changes to avoid hash skip)
        val updatedSnapshot = createSnapshot(
            listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to createEntity(0, PackageType.MINUTES, 101)))),
            isManual = true
        )
        manager.indexSnapshot(updatedSnapshot)

        // 3. Verify error status
        val status = manager.status.value
        assertTrue("Expected Error but was $status", status is TrackingStatus.Error)
        
        // 4. Fix failure and try again with SAME snapshot
        session.failPutDocuments = false
        manager.indexSnapshot(updatedSnapshot)

        // 5. Verify it tried again (meaning hash wasn't updated on failure)
        assertTrue(manager.status.value is TrackingStatus.Active)
        assertEquals(updatedSnapshot.simSnapshots[0].entities[PackageType.MINUTES]!!.remaining, 101L)
    }

    @Test
    fun `recover after error reinitializes session`() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val flow = MutableStateFlow(createEmptySnapshot())
        manager.initialize(flow)
        
        // Simulate fatal error that closes session
        fakeWrapper.failNextWithFatalError = true
        manager.recover() 
        
        assertTrue(manager.status.value is TrackingStatus.Error)
        
        // Fix and recover
        fakeWrapper.failNextWithFatalError = false
        manager.recover()
        
        assertTrue(manager.status.value is TrackingStatus.Active)
        assertEquals(3, fakeWrapper.createdSessions)
    }

    private fun createManager(scope: CoroutineScope) = object : SemanticSearchManager(
        context = context,
        scope = scope,
        appSearchWrapper = fakeWrapper,
        timeProvider = object : TimeProvider {
            override fun now(): Long = currentTime
            override fun elapsedRealtime(): Long = currentTime
        },
        ioDispatcher = testDispatcher
    ) {
        override fun createPutRequest(documents: List<UsageDocument>): PutDocumentsRequest {
            // Create PutDocumentsRequest manually with generic documents to avoid converter lookup
            val builder = PutDocumentsRequest.Builder()
            documents.forEach { doc ->
                builder.addGenericDocuments(
                    androidx.appsearch.app.GenericDocument.Builder<androidx.appsearch.app.GenericDocument.Builder<*>>(
                        doc.namespace, doc.id, "UsageDocument"
                    ).build()
                )
            }
            return builder.build()
        }

        override fun createRemoveRequest(ids: Set<String>): RemoveByDocumentIdRequest {
            return RemoveByDocumentIdRequest.Builder("usage").addIds(ids).build()
        }
    }

    private fun createEntity(slot: Int, type: PackageType, remaining: Long) = SemanticEntity(
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

    private fun createSnapshot(sims: List<SimSnapshot>, isManual: Boolean = false) = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = currentTime,
        simSnapshots = sims,
        capabilities = emptyList(),
        isManualUpdate = isManual
    )

    private fun createEmptySnapshot() = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = 0,
        simSnapshots = emptyList(),
        capabilities = emptyList()
    )

    private class FakeAppSearchWrapper : AppSearchWrapper {
        var createdSessions = 0
        var lastSession: FakeAppSearchSessionWrapper? = null
        var failNextWithInvalidSchema = false
        var failNextWithFatalError = false

        override suspend fun createSearchSession(context: Context, databaseName: String): AppSearchSessionWrapper {
            createdSessions++
            val session = FakeAppSearchSessionWrapper(this)
            lastSession = session
            return session
        }

        override fun createSchemaRequest(forceOverride: Boolean): SetSchemaRequest {
            // Return a dummy schema request that doesn't use document classes
            // to avoid missing converter error in tests
            return SetSchemaRequest.Builder()
                .setForceOverride(forceOverride)
                .addSchemas(AppSearchSchema.Builder("UsageDocument").build())
                .build()
        }
    }

    private class FakeAppSearchSessionWrapper(
        private val wrapper: FakeAppSearchWrapper
    ) : AppSearchSessionWrapper {
        var schemaSet = false
        var lastSchemaRequest: SetSchemaRequest? = null
        val putRequests = mutableListOf<PutDocumentsRequest>()
        val removeRequests = mutableListOf<RemoveByDocumentIdRequest>()
        var failPutDocuments = false

        override suspend fun setSchema(request: SetSchemaRequest) {
            if (wrapper.failNextWithInvalidSchema && !request.isForceOverride) {
                throw AppSearchException(AppSearchResult.RESULT_INVALID_SCHEMA)
            }
            if (wrapper.failNextWithFatalError) {
                throw AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR)
            }
            schemaSet = true
            lastSchemaRequest = request
        }

        override suspend fun putDocuments(request: PutDocumentsRequest) {
            if (failPutDocuments) {
                throw AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR, "Simulated failure")
            }
            putRequests.add(request)
        }

        override suspend fun removeByDocumentId(request: RemoveByDocumentIdRequest) {
            removeRequests.add(request)
        }

        override fun close() {}
    }
}



