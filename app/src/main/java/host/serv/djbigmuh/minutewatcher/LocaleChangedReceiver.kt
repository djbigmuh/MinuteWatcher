package host.serv.djbigmuh.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.AndroidEntryPoint
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.data.NotificationHelper
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

@AndroidEntryPoint
open class LocaleChangedReceiver : BroadcastReceiver() {
    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var dataChangeNotifier: DataChangeNotifier

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return

        val pendingResult = acquirePendingResult()
        goAsyncWithScope(context, pendingResult, dispatcher = asyncDispatcher()) { _ ->
            try {
                provideNotificationHelper().ensureChannels()
                provideDataChangeNotifier().notifyDataChanged(updateWidget = true, force = true)
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
    protected open fun provideNotificationHelper(): NotificationHelper = notificationHelper

    @VisibleForTesting
    protected open fun provideDataChangeNotifier(): DataChangeNotifier = dataChangeNotifier
}
