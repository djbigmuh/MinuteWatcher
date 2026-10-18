package host.serv.djbigmuh.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.data.NotificationHelper
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocaleRefreshEntryPoint {
    fun dataChangeNotifier(): DataChangeNotifier

    fun notificationHelper(): NotificationHelper
}

open class LocaleChangedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return

        val pendingResult = acquirePendingResult()
        goAsyncWithScope(context, pendingResult, dispatcher = asyncDispatcher()) { appContext ->
            try {
                provideNotificationHelper(appContext).ensureChannels()
                val notifier = provideDataChangeNotifier(appContext)
                notifier.notifyDataChanged(updateWidget = true, force = true)
                AppLogger.d("LocaleChangedReceiver: Data changed notified and channels updated successfully")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("LocaleChangedReceiver: Failed to notify data change", e)
            }
        }
    }

    @VisibleForTesting
    protected open fun acquirePendingResult(): PendingResult = goAsync()

    @VisibleForTesting
    protected open fun asyncDispatcher(): CoroutineDispatcher? = null

    @VisibleForTesting
    protected open suspend fun provideNotificationHelper(appContext: Context): NotificationHelper {
        val entryPoint = EntryPointAccessors.fromApplication(appContext, LocaleRefreshEntryPoint::class.java)
        return entryPoint.notificationHelper()
    }

    @VisibleForTesting
    protected open suspend fun provideDataChangeNotifier(appContext: Context): DataChangeNotifier {
        val entryPoint = EntryPointAccessors.fromApplication(appContext, LocaleRefreshEntryPoint::class.java)
        return entryPoint.dataChangeNotifier()
    }
}
