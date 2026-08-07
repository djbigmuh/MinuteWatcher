package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import kotlinx.coroutines.guava.await
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
}

/**
 * Wrapper for [AppSearchSession] to simplify suspend calls.
 */
interface AppSearchSessionWrapper : AutoCloseable {
    val isClosed: Boolean

    suspend fun setSchema(request: SetSchemaRequest)

    suspend fun putDocuments(request: PutDocumentsRequest)

    suspend fun removeByDocumentId(request: RemoveByDocumentIdRequest)
}

@Singleton
class AppSearchWrapperImpl
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
    }

private class AppSearchSessionWrapperImpl(
    private val session: AppSearchSession,
) : AppSearchSessionWrapper {
    override var isClosed = false
        private set

    override suspend fun setSchema(request: SetSchemaRequest) {
        session.setSchemaAsync(request).await()
    }

    override suspend fun putDocuments(request: PutDocumentsRequest) {
        val result = session.putAsync(request).await()
        if (!result.isSuccess) {
            val firstError = result.failures.values.firstOrNull()
            error("Failed to put documents: ${firstError?.errorMessage}")
        }
    }

    override suspend fun removeByDocumentId(request: RemoveByDocumentIdRequest) {
        val result = session.removeAsync(request).await()
        if (!result.isSuccess) {
            val firstError = result.failures.values.firstOrNull()
            error("Failed to remove documents: ${firstError?.errorMessage}")
        }
    }

    override fun close() {
        if (!isClosed) {
            session.close()
            isClosed = true
        }
    }
}
