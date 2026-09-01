package com.example.minutewatcher.core.common.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Обобщенный класс для управления циклом наблюдения за потоком данных.
 * Реализует паттерн управления джобом: отмена старого, запуск нового,
 * сохранение последнего потока для восстановления.
 */
class FlowObservationScope<T>(
    private val scope: CoroutineScope,
    private val tag: String,
) {
    private val mutex = Mutex()
    private var lastFlow: Flow<T>? = null
    private var lastCollector: (suspend (T) -> Unit)? = null
    private var lastContext: CoroutineContext = EmptyCoroutineContext
    private var currentJob: Job? = null

    /**
     * Запускает наблюдение за потоком. Отменяет предыдущий джоб, если он был.
     */
    suspend fun observe(
        flow: Flow<T>,
        context: CoroutineContext = EmptyCoroutineContext,
        collector: suspend (T) -> Unit,
    ): Job =
        mutex.withLock {
            lastFlow = flow
            lastCollector = collector
            lastContext = context

            currentJob?.cancel()
            val newJob =
                (scope + SupervisorJob() + context).launch {
                    flow.collect { collector(it) }
                }
            currentJob = newJob
            newJob
        }

    /**
     * Перезапускает наблюдение, используя последний известный поток и коллектор.
     */
    suspend fun recover(): Job? =
        mutex.withLock {
            val flow = lastFlow
            val collector = lastCollector
            if ((flow != null) && (collector != null)) {
                currentJob?.cancel()
                val newJob =
                    (scope + SupervisorJob() + lastContext).launch {
                        flow.collect { collector(it) }
                    }
                currentJob = newJob
                newJob
            } else {
                AppLogger.w("FlowObservationScope [$tag]: recovery skipped, no flow or collector registered")
                null
            }
        }

    /**
     * Останавливает наблюдение и очищает ссылки.
     */
    suspend fun shutdown() {
        mutex.withLock {
            currentJob?.cancel()
            currentJob = null
            lastFlow = null
            lastCollector = null
        }
    }

    /**
     * Возвращает текущий активный джоб (только для чтения/мониторинга).
     */
    fun getJob(): Job? = currentJob
}
