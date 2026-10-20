package host.serv.djbigmuh.minutewatcher.feature.semantic.search

import android.content.Context
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import dagger.hilt.android.qualifiers.ApplicationContext
import host.serv.djbigmuh.minutewatcher.core.model.TrackingStatus
import kotlinx.coroutines.CancellationException
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

        @Volatile
        var session: AppSearchSessionWrapper? = null
            private set

        suspend fun ensureSession(
            updateStatus: (TrackingStatus) -> Unit,
            forceOverride: Boolean = false,
        ): Boolean =
            sessionMutex.withLock {
                val s = session
                if ((s != null) && (!s.isClosed) && (!forceOverride)) return true

                if (session?.isClosed == false) {
                    session?.close()
                }
                session = null

                if (forceOverride) return tryOpenSession(updateStatus, forceOverride = true).isSuccess

                val firstTry = tryOpenSession(updateStatus, forceOverride = false)
                if (firstTry.isSuccess) return true

                val error = firstTry.exceptionOrNull()
                if ((error is AppSearchException) && (error.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA)) {
                    return tryOpenSession(updateStatus, forceOverride = true).isSuccess
                }
                false
            }

        private suspend fun tryOpenSession(
            updateStatus: (TrackingStatus) -> Unit,
            forceOverride: Boolean,
        ): Result<Unit> {
            var newSession: AppSearchSessionWrapper? = null
            return try {
                newSession = appSearchWrapper.createSearchSession(context, AppSearchConstants.DATABASE_NAME)
                newSession.setSchema(appSearchWrapper.createSchemaRequest(forceOverride))
                session = newSession
                updateStatus(TrackingStatus.Active)
                Result.success(Unit)
            } catch (e: CancellationException) {
                newSession?.close()
                throw e
            } catch (e: AppSearchException) {
                newSession?.close()
                updateStatus(errorHandler.handleSchemaException(e, forceOverride, TrackingStatus.Idle))
                Result.failure(e)
            } catch (e: Exception) {
                newSession?.close()
                updateStatus(errorHandler.handleInitializationError(e))
                Result.failure(e)
            }
        }

        suspend fun closeSession() {
            sessionMutex.withLock {
                session?.close()
                session = null
            }
        }
    }
