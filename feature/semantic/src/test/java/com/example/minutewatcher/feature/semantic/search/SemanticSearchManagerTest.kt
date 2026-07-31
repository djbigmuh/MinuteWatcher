package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.exceptions.AppSearchException
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    @Test
    fun `successful initialization sets status to Active`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val flow = MutableStateFlow(createEmptySnapshot())

            manager.initialize(flow)

            assertTrue("Status was ${manager.status.value}", manager.status.value is TrackingStatus.Active)
            assertEquals(1, fakeWrapper.createdSessions)
            assertNotNull(fakeWrapper.lastSession)
            assertTrue(fakeWrapper.lastSession!!.schemaSet)
        }

    @Test
    fun `schema mismatch triggers force override`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failNextWithInvalidSchema = true

            val flow = MutableStateFlow(createEmptySnapshot())
            manager.initialize(flow)

            assertTrue(manager.status.value is TrackingStatus.Active)
            assertEquals(2, fakeWrapper.createdSessions)
            assertTrue(fakeWrapper.lastSession!!.lastSchemaRequest!!.isForceOverride)
        }

    @Test
    fun `fatal error during init sets status to Error`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            fakeWrapper.failNextWithFatalError = true

            val flow = MutableStateFlow(createEmptySnapshot())
            manager.initialize(flow)

            val status = manager.status.value
            assertTrue("Expected Error but was $status", status is TrackingStatus.Error)
            assertEquals(TrackingStatus.ErrorType.DATABASE_ERROR, (status as TrackingStatus.Error).type)
        }

    @Test
    fun `indexSnapshot hash check skips identical data`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)

            val s = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to createEntity(0, PackageType.MINUTES, 100)))))
            manager.indexSnapshot(s)
            val session = fakeWrapper.lastSession!!
            val initialPuts = session.putRequests.size

            manager.indexSnapshot(s)
            assertEquals("Should skip due to identical hash", initialPuts, session.putRequests.size)
        }

    @Test
    fun `putDocuments failure sets error status and does not update hash`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to createEntity(0, PackageType.MINUTES, 100)))))

            manager.indexSnapshot(snapshot)
            val session = fakeWrapper.lastSession!!
            session.failPutDocuments = true

            val updatedSnapshot = createSnapshot(listOf(SimSnapshot(0, "A", true, mapOf(PackageType.MINUTES to createEntity(0, PackageType.MINUTES, 101)))))
            manager.indexSnapshot(updatedSnapshot)

            assertTrue(manager.status.value is TrackingStatus.Error)

            session.failPutDocuments = false
            manager.indexSnapshot(updatedSnapshot)

            assertTrue(manager.status.value is TrackingStatus.Active)
            assertEquals(2, session.putRequests.size)
        }

    @Test
    fun `indexSnapshot works with slot 1`() =
        runTest(testDispatcher) {
            val manager = createManager(backgroundScope)
            val snapshot = createSnapshot(listOf(SimSnapshot(1, "B", true, mapOf(PackageType.DATA to createEntity(1, PackageType.DATA, 50)))))
            manager.indexSnapshot(snapshot)

            val lastPut = fakeWrapper.lastSession!!.putRequests.last()
            assertTrue(lastPut.genericDocuments.isNotEmpty())
        }

    private fun createManager(scope: CoroutineScope) =
        object : SemanticSearchManager(
            context = context,
            scope = scope,
            appSearchWrapper = fakeWrapper,
            timeProvider =
                object : TimeProvider {
                    override fun now(): Long = currentTime
                    override fun elapsedRealtime(): Long = currentTime
                    override fun uptimeMillis(): Long = currentTime
                },
            ioDispatcher = testDispatcher,
        ) {
            override fun createPutRequest(documents: List<UsageDocument>): PutDocumentsRequest {
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

            override fun createRemoveRequest(ids: Set<String>): RemoveByDocumentIdRequest =
                RemoveByDocumentIdRequest.Builder("usage").addIds(ids).build()
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

    private fun createSnapshot(sims: List<SimSnapshot>) = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = currentTime,
        simSnapshots = sims,
        capabilities = emptyList(),
        isManualUpdate = true,
    )

    private fun createEmptySnapshot() = GlobalSnapshot(
        version = SchemaVersion(1, 0, 0),
        timestamp = 0,
        simSnapshots = emptyList(),
        capabilities = emptyList(),
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
            return SetSchemaRequest.Builder()
                .setForceOverride(forceOverride)
                .addSchemas(AppSearchSchema.Builder("UsageDocument").build())
                .build()
        }
    }

    private class FakeAppSearchSessionWrapper(private val wrapper: FakeAppSearchWrapper) : AppSearchSessionWrapper {
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
