package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import com.example.minutewatcher.core.common.di.CommonEntryPoint
import com.example.minutewatcher.core.common.util.AppLogger
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 */
fun goAsyncWithScope(
    context: Context,
    pendingResult: BroadcastReceiver.PendingResult,
    dispatcher: CoroutineDispatcher? = null,
    block: suspend (appContext: Context) -> Unit,
) {
    val appContext = context.applicationContext
    val finalDispatcher =
        dispatcher ?: try {
            EntryPointAccessors
                .fromApplication(
                    appContext,
                    CommonEntryPoint::class.java,
                ).mainImmediateDispatcher()
        } catch (e: Exception) {
            // Fallback for tests not using Hilt or missing CommonEntryPoint
            AppLogger.e("ReceiverCoroutineHelper: CommonEntryPoint access failed, using Main.immediate", e)
            Dispatchers.Main.immediate
        }

    CoroutineScope(finalDispatcher + SupervisorJob()).launch {
        try {
            val completed =
                withTimeoutOrNull(RECEIVER_ASYNC_TIMEOUT_MS.milliseconds) {
                    block(appContext)
                    true
                } ?: false
            if (!completed) {
                AppLogger.e("ReceiverCoroutineHelper: Async block timed out after ${RECEIVER_ASYNC_TIMEOUT_MS}ms")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Receiver async block failed", e)
        } finally {
            pendingResult.finish()
        }
    }
}
