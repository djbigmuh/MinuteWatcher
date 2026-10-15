package com.example.minutewatcher

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetDataChangeDispatcher
@Inject
constructor(
    private val dataChangeNotifier: DataChangeNotifier,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    fun notifyDataChanged(reason: String): Job =
        applicationScope.launch {
            try {
                dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("WidgetDataChangeDispatcher: Failed to notify data change on $reason", e)
            }
        }
}
