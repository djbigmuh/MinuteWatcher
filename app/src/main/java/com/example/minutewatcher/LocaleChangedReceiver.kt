package com.example.minutewatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocaleRefreshEntryPoint {
    fun dataChangeNotifier(): DataChangeNotifier
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
                val notifier = provideDataChangeNotifier(appContext)
                notifier.notifyDataChanged(updateWidget = true, force = true)
                AppLogger.d("LocaleChangedReceiver: Data changed notified successfully")
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
    protected open suspend fun provideDataChangeNotifier(appContext: Context): DataChangeNotifier {
        val entryPoint = EntryPointAccessors.fromApplication(appContext, LocaleRefreshEntryPoint::class.java)
        return entryPoint.dataChangeNotifier()
    }
}
