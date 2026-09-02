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

/**
 * Executes a coroutine block within a [BroadcastReceiver.goAsync] context.
 *
 * [goAsync] extends the receiver's lifetime until `PendingResult.finish()` is called.
 * This is required for asynchronous operations (like WorkManager enqueuing) because
 * without it, the system may kill the receiver process prematurely.
 *
 * **Строгое соблюдение API:** BroadcastReceiver с goAsync() должен вызвать
 * PendingResult.finish() до завершения. SupervisorJob гарантирует, что
 * отмена одной корутины не повлияет на другие. finish() вызывается в
 * finally, поэтому даже при отмене корутины он будет вызван.
 *
 * На AOSP BroadcastReceiver с goAsync() живёт до вызова finish().
 * Если кастомная прошивка убивает процесс до вызова finish() —
 * это проблема кастомной прошивки.
 *
 * @param context The context provided to [BroadcastReceiver.onReceive].
 * @param pendingResult The [BroadcastReceiver.PendingResult] obtained from [BroadcastReceiver.goAsync].
 * @param block The suspend block to execute. Receives application context.
 */
fun BroadcastReceiver.goAsyncWithScope(
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
            block(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Receiver async block failed", e)
        } finally {
            pendingResult.finish()
        }
    }
}
