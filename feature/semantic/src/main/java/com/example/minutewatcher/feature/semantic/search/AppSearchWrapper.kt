package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SearchResult
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.exceptions.AppSearchException
import androidx.appsearch.localstorage.LocalStorage
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.feature.semantic.search.AppSearchConstants.NAMESPACE
import kotlinx.coroutines.guava.await
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface to wrap AppSearch operations for better testability and error handling.
 */
interface AppSearchWrapper {
    suspend fun createSearchSession(
        context: Context,
        databaseName: String,
    ): AppSearchSessionWrapper

    fun createSchemaRequest(forceOverride: Boolean): SetSchemaRequest

    fun createPutRequest(documents: List<UsageDocument>): PutDocumentsRequest

    fun createRemoveRequest(ids: Set<String>): RemoveByDocumentIdRequest
}

/**
 * Wrapper for [AppSearchSession] to simplify suspend calls.
 */
interface AppSearchSessionWrapper : AutoCloseable {
    val isClosed: Boolean

    suspend fun setSchema(request: SetSchemaRequest)

    suspend fun putDocuments(request: PutDocumentsRequest)

    suspend fun removeByDocumentId(request: RemoveByDocumentIdRequest)

    suspend fun query(
        queryExpression: String,
        spec: SearchSpec,
    ): List<SearchResult>
}

@Singleton
internal class AppSearchWrapperImpl
    @Inject
    constructor() : AppSearchWrapper {
        override suspend fun createSearchSession(
            context: Context,
            databaseName: String,
        ): AppSearchSessionWrapper {
            val future =
                LocalStorage.createSearchSessionAsync(
                    LocalStorage.SearchContext.Builder(context, databaseName).build(),
                )
            return AppSearchSessionWrapperImpl(future.await())
        }

        override fun createSchemaRequest(forceOverride: Boolean): SetSchemaRequest =
            SetSchemaRequest
                .Builder()
                .setForceOverride(forceOverride)
                .addDocumentClasses(UsageDocument::class.java)
                .build()

        override fun createPutRequest(documents: List<UsageDocument>): PutDocumentsRequest =
            PutDocumentsRequest
                .Builder()
                .addDocuments(documents)
                .build()

        override fun createRemoveRequest(ids: Set<String>): RemoveByDocumentIdRequest =
            RemoveByDocumentIdRequest
                .Builder(NAMESPACE)
                .addIds(ids)
                .build()
    }

internal class AppSearchSessionWrapperImpl(
    private val session: AppSearchSession,
) : AppSearchSessionWrapper {
    private val closed = AtomicBoolean(false)
    override val isClosed: Boolean get() = closed.get()

    override suspend fun setSchema(request: SetSchemaRequest) {
        session.setSchemaAsync(request).await()
    }

    /**
     * Saves documents in AppSearch.
     *
     * In case of partial success (some documents failed to add), throws [AppSearchException].
     * This is intentional behavior: in [SemanticSearchManager] it leads to a reset of `lastSnapshotHash`,
     * guaranteeing full re-indexing of the snapshot on the next call and preventing
     * index desynchronization with the actual package state.
     */
    override suspend fun putDocuments(request: PutDocumentsRequest) {
        val result = session.putAsync(request).await()
        if (!result.isSuccess) {
            val details = result.failures.entries.joinToString("; ") { "${it.key}: ${it.value.errorMessage}" }
            AppLogger.w("Partial put failure in AppSearch: $details")
            throw AppSearchException(
                AppSearchResult.RESULT_INTERNAL_ERROR,
                "Failed to put documents: $details",
            )
        }
    }

    override suspend fun removeByDocumentId(request: RemoveByDocumentIdRequest) {
        val result = session.removeAsync(request).await()
        if (!result.isSuccess) {
            val firstError = result.failures.values.firstOrNull()
            throw AppSearchException(
                AppSearchResult.RESULT_INTERNAL_ERROR,
                "Failed to remove documents: ${firstError?.errorMessage}",
            )
        }
    }

    override suspend fun query(
        queryExpression: String,
        spec: SearchSpec,
    ): List<SearchResult> {
        val searchResults = session.search(queryExpression, spec)
        return searchResults.nextPageAsync.await()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            session.close()
        }
    }
}
