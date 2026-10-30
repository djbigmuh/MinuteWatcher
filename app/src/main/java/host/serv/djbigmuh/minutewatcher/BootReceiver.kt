package host.serv.djbigmuh.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.AndroidEntryPoint
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.data.WorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.guava.await
import javax.inject.Inject

@AndroidEntryPoint
open class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var workScheduler: WorkScheduler

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = acquirePendingResult()
        goAsyncWithScope(context, pendingResult, dispatcher = asyncDispatcher()) { _ ->
            try {
                // Force REPLACE on boot to ensure fresh initialization after restart.
                // We await the enqueue operation to ensure it persists before finish().
                provideWorkScheduler().scheduleInitialSetup(force = true, triggeredByBoot = true).result.await()
                AppLogger.d("BootReceiver: InitialSetup scheduled successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("BootReceiver: Failed to schedule InitialSetup", e)
            }
        }
    }

    @VisibleForTesting
    protected open fun acquirePendingResult(): PendingResult = goAsync()

    @VisibleForTesting
    protected open fun provideWorkScheduler(): WorkScheduler = workScheduler

    @VisibleForTesting
    protected open fun asyncDispatcher(): CoroutineDispatcher? = null
}
