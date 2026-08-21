package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.model.TrackingStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages AppSearch session lifecycle.
 *
 * Safe to call from any coroutine context.
 */
@Singleton
class AppSearchSessionManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val appSearchWrapper: AppSearchWrapper,
        private val errorHandler: AppSearchErrorHandler,
    ) {
        private val sessionMutex = Mutex()
        var session: AppSearchSessionWrapper? = null
            private set

        suspend fun ensureSession(
            status: MutableStateFlow<TrackingStatus>,
            forceOverride: Boolean = false,
        ): Boolean =
            sessionMutex.withLock {
                val s = session
                if ((s != null) && (!s.isClosed) && (!forceOverride)) return true

                session?.close()
                session = null

                if (forceOverride) return tryOpenSession(status, forceOverride = true).isSuccess

                val firstTry = tryOpenSession(status, forceOverride = false)
                if (firstTry.isSuccess) return true

                val error = firstTry.exceptionOrNull()
                if ((error is AppSearchException) && (error.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA)) {
                    return tryOpenSession(status, forceOverride = true).isSuccess
                }
                false
            }

        private suspend fun tryOpenSession(
            status: MutableStateFlow<TrackingStatus>,
            forceOverride: Boolean,
        ): Result<Unit> {
            var newSession: AppSearchSessionWrapper? = null
            return try {
                newSession = appSearchWrapper.createSearchSession(context, DATABASE_NAME)
                newSession.setSchema(appSearchWrapper.createSchemaRequest(forceOverride))
                session = newSession
                status.value = TrackingStatus.Active
                Result.success(Unit)
            } catch (e: CancellationException) {
                newSession?.close()
                throw e
            } catch (e: AppSearchException) {
                newSession?.close()
                errorHandler.handleSchemaException(e, forceOverride, status)
                Result.failure(e)
            } catch (e: Exception) {
                newSession?.close()
                errorHandler.handleInitializationError(e, status)
                Result.failure(e)
            }
        }

        fun closeSession() {
            session?.close()
            session = null
        }

        companion object {
            private const val DATABASE_NAME = "usage"
        }
    }
