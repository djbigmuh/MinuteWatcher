package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.util.SnapshotUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
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
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class UpdateCoordinator
    @Inject
    constructor(
        private val dataRefreshNotifier: DataRefreshNotifier,
        private val widgetUpdater: WidgetUpdater,
        private val usageStateRepository: UsageStateRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DataChangeNotifier {
        private val forceCounter = AtomicLong(0)

        private val forceUpdateFlow =
            MutableSharedFlow<Long>(
                replay = 1,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val startCount = forceCounter.get()
                var lastProcessedHash: Int? = null

                val snapshotFlow =
                    usageStateRepository
                        .observeSnapshot()
                        .filter { it.timestamp > 0L }
                        .distinctUntilChanged { old, new ->
                            SnapshotUtils.calculateContentHash(old) ==
                                SnapshotUtils.calculateContentHash(new)
                        }.debounce(Constants.Widget.DEBOUNCE_MS.milliseconds)
                        .map { UpdateEvent.Snapshot(SnapshotUtils.calculateContentHash(it), it) }

                val forceFlow =
                    forceUpdateFlow
                        .filter { it > startCount }
                        .map { UpdateEvent.Force }

                merge(snapshotFlow, forceFlow).collect { event ->
                    try {
                        val currentHash =
                            when (event) {
                                is UpdateEvent.Force ->
                                    SnapshotUtils.calculateContentHash(usageStateRepository.observeSnapshot().value)

                                is UpdateEvent.Snapshot -> event.hash
                            }

                        if (event is UpdateEvent.Force || currentHash != lastProcessedHash) {
                            widgetUpdater.updateAll()
                            lastProcessedHash = currentHash
                        }

                        if (event is UpdateEvent.Force) {
                            forceUpdateFlow.resetReplayCache()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("Widget update failed in coordinator", e)
                    }
                }
            }
        }

        private sealed class UpdateEvent {
            object Force : UpdateEvent()

            data class Snapshot(
                val hash: Int,
                val snapshot: GlobalSnapshot,
            ) : UpdateEvent()
        }

        /**
         * Уведомляет об изменении данных.
         */
        override suspend fun notifyDataChanged(
            updateWidget: Boolean,
            force: Boolean,
        ) {
            if (updateWidget && force) {
                val emitted = forceUpdateFlow.tryEmit(forceCounter.incrementAndGet())
                if (!emitted) {
                    AppLogger.w("UpdateCoordinator: force update signal dropped, buffer full")
                    // Fallback: прямое обновление
                    widgetUpdater.updateAll()
                }
            }
            dataRefreshNotifier.notifyRefresh(isManual = force)
        }
    }
