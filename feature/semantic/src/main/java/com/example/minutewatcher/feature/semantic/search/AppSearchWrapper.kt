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

interface AppSearchWrapper {
    suspend fun createSearchSession(
        context: Context,
        databaseName: String,
    ): AppSearchSessionWrapper

    fun createSchemaRequest(forceOverride: Boolean): SetSchemaRequest
}

interface AppSearchSessionWrapper : AutoCloseable {
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
    override suspend fun setSchema(request: SetSchemaRequest) {
        session.setSchemaAsync(request).await()
    }

    override suspend fun putDocuments(request: PutDocumentsRequest) {
        session.putAsync(request).await()
    }

    override suspend fun removeByDocumentId(request: RemoveByDocumentIdRequest) {
        session.removeAsync(request).await()
    }

    override fun close() {
        session.close()
    }
}
