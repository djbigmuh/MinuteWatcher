package host.serv.djbigmuh.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import dagger.hilt.android.EntryPointAccessors
import host.serv.djbigmuh.minutewatcher.core.common.di.CommonEntryPoint
import host.serv.djbigmuh.minutewatcher.core.common.di.IoDispatcherFallback
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * AOSP limits goAsync() receiver execution to ~10 seconds.
 * 8 seconds provides a safety margin to call finish() before forced system termination.
 */
private const val RECEIVER_ASYNC_TIMEOUT_MS = 8_000L

/**
 * Executes a coroutine block within a [BroadcastReceiver.goAsync] context.
 *
 * [BroadcastReceiver.goAsync] extends the receiver's lifetime until `PendingResult.finish()` is called.
 * This is required for asynchronous operations (like WorkManager enqueuing) because
 * without it, the system may kill the receiver process prematurely.
 *
 * @param context The context provided to [BroadcastReceiver.onReceive].
 * @param pendingResult The [BroadcastReceiver.PendingResult] obtained from [BroadcastReceiver.goAsync].
 * @param dispatcher Optional dispatcher override for testing.
 * @param block The suspend block to execute. Receives application context.
 * @return The launched [Job] for deterministic tracking and scope completion.
 */
fun goAsyncWithScope(
    context: Context,
    pendingResult: BroadcastReceiver.PendingResult,
    dispatcher: CoroutineDispatcher? = null,
    block: suspend (appContext: Context) -> Unit,
): Job {
    val finished = AtomicBoolean(false)

    fun finishOnce() {
        if (finished.compareAndSet(false, true)) {
            try {
                pendingResult.finish()
            } catch (
                // Catch Exception to ensure finish completes without crashing the runtime.
                @Suppress("TooGenericExceptionCaught")
                e: Exception,
            ) {
                AppLogger.e("ReceiverCoroutineHelper: finish failed", e)
            }
        }
    }

    var coroutineLaunched = false
    try {
        val appContext = context.applicationContext
        val finalDispatcher =
            dispatcher ?: try {
                EntryPointAccessors
                    .fromApplication(
                        appContext,
                        CommonEntryPoint::class.java,
                    ).ioDispatcher()
            } catch (
                // Catch Exception to fall back gracefully if Hilt entry point is unavailable during broadcast handling.
                @Suppress("TooGenericExceptionCaught")
                e: Exception,
            ) {
                AppLogger.e(
                    "ReceiverCoroutineHelper: CommonEntryPoint access failed, using IO dispatcher fallback",
                    e,
                )
                IoDispatcherFallback.io
            }

        val supervisorJob = SupervisorJob()
        val scope = CoroutineScope(finalDispatcher + supervisorJob)

        val job =
            scope.launch {
                try {
                    val completed =
                        withTimeoutOrNull(RECEIVER_ASYNC_TIMEOUT_MS.milliseconds) {
                            block(appContext)
                            true
                        } ?: false
                    if (!completed) {
                        AppLogger.e(
                            "ReceiverCoroutineHelper: Async block timed out after ${RECEIVER_ASYNC_TIMEOUT_MS}ms",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    // Catch Exception to log asynchronous execution failures without unhandled exception propagation.
                    @Suppress("TooGenericExceptionCaught")
                    e: Exception,
                ) {
                    AppLogger.e("Receiver async block failed", e)
                } finally {
                    try {
                        finishOnce()
                    } finally {
                        supervisorJob.cancel()
                    }
                }
            }
        coroutineLaunched = true
        return job
    } finally {
        if (!coroutineLaunched) {
            finishOnce()
        }
    }
}
