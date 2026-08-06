package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Единый механизм обновления.
 *
 * Координатор является Singleton и использует ApplicationScope, поэтому цикл наблюдения
 * за снимками состояния (observeSnapshot) живет в течение всего времени работы приложения
 * после первого вызова [notifyDataChanged].
 */
@OptIn(FlowPreview::class)
@Singleton
class UpdateCoordinator
    @Inject
    constructor(
        private val dataRefreshNotifier: DataRefreshNotifier,
        private val widgetUpdater: WidgetUpdater,
        private val usageStateRepository: UsageStateRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DataChangeNotifier {
        private val forceUpdateChannel = Channel<Unit>(Channel.UNLIMITED)

        init {
            scope.launch {
                val snapshotFlow =
                    usageStateRepository
                        .observeSnapshot()
                        .filter { it.timestamp > 0L }
                        .distinctUntilChanged { old, new ->
                            SnapshotUtils.calculateContentHash(old) ==
                                SnapshotUtils.calculateContentHash(new)
                        }.debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)

                val forceFlow = forceUpdateChannel.receiveAsFlow()

                merge(snapshotFlow, forceFlow).collect {
                    try {
                        widgetUpdater.updateAll()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Widget update failed in coordinator", e)
                    }
                }
            }
        }

        /**
         * Уведомляет об изменении данных.
         */
        override suspend fun notifyDataChanged(
            updateWidget: Boolean,
            force: Boolean,
        ) {
            if (updateWidget && force) {
                forceUpdateChannel.send(Unit)
            }
            dataRefreshNotifier.notifyRefresh(isManual = force)
        }
    }
