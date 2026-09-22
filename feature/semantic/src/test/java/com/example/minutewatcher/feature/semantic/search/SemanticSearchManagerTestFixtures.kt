package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.android.testing.SingletonResetRule
import com.example.minutewatcher.core.common.testing.InMemoryPreferencesDataStore
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.initTestLogger
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SchemaVersion
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.SimSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
abstract class SemanticSearchManagerTestFixtures {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

    protected val testScheduler = TestCoroutineScheduler()
    protected val testDispatcher = StandardTestDispatcher(testScheduler)

    /**
     * Test double for the application scope.
     *
     * The property name must not be `backgroundScope`: inside a `runTest { }` block that identifier
     * resolves to `TestScope.backgroundScope`, whose scheduled tasks are skipped by
     * `TestCoroutineScheduler.advanceUntilIdle()`, making observation-based assertions untestable.
     */
    protected val appScope = CoroutineScope(testDispatcher + Job())

    protected lateinit var context: Context
    protected val timeProvider: TimeProvider = mock()
    protected var currentTime = 1000000L

    protected lateinit var fakeWrapper: FakeAppSearchWrapper
    protected lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    @Before
    open fun setup() {
        initTestLogger()
        context = mock()
        whenever(timeProvider.now()).thenAnswer { currentTime + testScheduler.currentTime }
        whenever(timeProvider.elapsedRealtime()).thenAnswer { currentTime + testScheduler.currentTime }
        whenever(timeProvider.uptimeMillis()).thenAnswer { currentTime + testScheduler.currentTime }

        fakeWrapper = FakeAppSearchWrapper()
        dataStore = InMemoryPreferencesDataStore()
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    protected fun createManager(scope: CoroutineScope) =
        SemanticSearchManager.createForTest(
            context = context,
            scope = scope,
            appSearchWrapper = fakeWrapper,
            ioDispatcher = testDispatcher,
            dataStore = dataStore,
            timeProvider = timeProvider,
        )

    protected fun createEntity(
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

    protected fun createSnapshot(sims: List<SimSnapshot>) =
        GlobalSnapshot(
            version = SchemaVersion(1, 0, 0),
            timestamp = currentTime,
            simSnapshots = sims,
            capabilities = emptyList(),
            isManualUpdate = true,
        )

    protected fun createTestSnapshot(remaining: Long) =
        createSnapshot(
            listOf(
                SimSnapshot(
                    slot = 0,
                    identity = "sim_0",
                    operatorName = "A",
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

    protected fun createSnapshotWithOneSim() = createTestSnapshot(100)

    protected class FakeAppSearchWrapper : AppSearchWrapper {
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

    protected class FakeAppSearchSessionWrapper(
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
        var queryResult: List<androidx.appsearch.app.SearchResult> = emptyList()

        @Suppress("ThrowsCount")
        override suspend fun setSchema(request: SetSchemaRequest) {
            val exceptionCode =
                when {
                    wrapper.failNextWithInvalidSchema && !request.isForceOverride ->
                        AppSearchResult.RESULT_INVALID_SCHEMA

                    wrapper.failNextWithFatalError ->
                        AppSearchResult.RESULT_INTERNAL_ERROR

                    else -> null
                }

            if (exceptionCode != null) {
                throw AppSearchException(exceptionCode)
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

        override suspend fun query(
            queryExpression: String,
            spec: androidx.appsearch.app.SearchSpec,
        ): List<androidx.appsearch.app.SearchResult> = queryResult

        override fun close() {
            isClosed = true
        }
    }

    protected fun getErrorHandler(manager: SemanticSearchManager): AppSearchErrorHandler = manager.errorHandler
}
