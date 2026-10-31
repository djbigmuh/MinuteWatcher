package host.serv.djbigmuh.minutewatcher

import host.serv.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
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
                @Suppress("TooGenericExceptionCaught")
                try {
                    dataChangeNotifier.notifyDataChanged(updateWidget = true, force = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for widget notification reliability.
                    AppLogger.e("WidgetDataChangeDispatcher: Failed to notify data change on $reason", e)
                }
            }
    }
