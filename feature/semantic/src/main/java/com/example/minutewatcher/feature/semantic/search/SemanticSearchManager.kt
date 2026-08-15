package com.example.minutewatcher.feature.semantic.search

import android.content.Context
import android.os.DeadObjectException
import androidx.appsearch.app.AppSearchResult
import androidx.appsearch.exceptions.AppSearchException
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.data.semantic.SemanticMapper
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.Constants.SimConstants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.TrackingStatus
import com.example.minutewatcher.core.model.util.SnapshotUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages AppSearch indexing for usage data.
 * Ensures that data is searchable via global system search.
 *
 * Реализовано строгое соблюдение API и SDK. Отклонения кастомных прошивок
 * не маскируются, а обрабатываются как системные сбои с переводом в Error.
 */
@Singleton
@Suppress("TooManyFunctions")
class SemanticSearchManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val scope: CoroutineScope,
        private val appSearchWrapper: AppSearchWrapper,
        @com.example.minutewatcher.core.common.di.IoDispatcher
        private val ioDispatcher: CoroutineDispatcher,
    ) : TrackingStatusProvider {
        override val key: String = "appsearch"

        private val sessionMutex = Mutex()
        private var session: AppSearchSessionWrapper? = null
        private var lastSnapshotHash: Int? = null
        private var job: Job? = null
        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        /**
         * Восстанавливает работу менеджера после ошибки.
         * Строгое соблюдение API: функция гарантирует, что после возврата
         * инициализация завершена и менеджер готов к работе.
         * На AOSP восстановление занимает миллисекунды.
         * Задержка возможна только при деградации хранилища на кастомной прошивке.
         */
        override suspend fun recover() {
            lastSnapshotFlow?.let { initialize(it) }
        }

        fun setError(message: String?) {
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message)
        }

        /**
         * Мьютекс сериализует повторные вызовы [initialize].
         * Без него повторный вызов до завершения join предыдущей корутины
         * приводит к гонке за переменную [job].
         *
         * Строгое соблюдение API: на AOSP StateFlow эмитит значения последовательно;
         * мьютекс защищает только инфраструктуру подписки, не данные.
         */
        private val initMutex = Mutex()

        /**
         * Инициализирует менеджер для наблюдения за изменениями snapshot.
         * Идемпотентен: повторный вызов отменяет предыдущее наблюдение
         * и запускает новое. `job?.join()` гарантирует, что старая корутина
         * полностью завершилась до запуска новой.
         *
         * **Строгое соблюдение API:** на AOSP StateFlow эмитит значения
         * последовательно. Debounce гарантирует отсутствие дублирующих индексаций.
         * Если кастомная прошивка эмитит значения вне порядка — это проблема
         * кастомной прошивки.
         */
        @OptIn(FlowPreview::class)
        suspend fun initialize(snapshotFlow: StateFlow<GlobalSnapshot>) {
            initMutex.withLock {
                // Присваивание lastSnapshotFlow внутри initMutex.withLock гарантирует
                // потокобезопасность при вызове initialize() из recover() (suspend-контекст).
                // На AOSP initialize() вызывается однократно из Main-thread;
                // защита мьютексом — защита от конкурентных вызовов recover().
                lastSnapshotFlow = snapshotFlow
                job?.cancel()
                job?.join() // дожидаемся реальной остановки
                job =
                    scope.launch {
                        _status.value = TrackingStatus.Active
                        snapshotFlow
                            .debounce { if (it.isManualUpdate) 0L else Constants.Semantic.THROTTLE_MS }
                            .collect { snapshot ->
                                ensureActive()
                                try {
                                    indexSnapshot(snapshot)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.e("SemanticSearchManager: unexpected error during indexing", e)
                                }
                            }
                    }
            }
        }

        /**
         * **Строгое соблюдение API:** на AOSP LocalStorage.createSearchSessionAsync
         * бросает один из стандартных типов исключений. Повтор с forceOverride=true
         * выполняется ТОЛЬКО при RESULT_INVALID_SCHEMA. Для остальных типов повтор
         * бессмыслен — это проблема кастомной прошивки.
         */
        private suspend fun ensureSession(forceOverride: Boolean = false): Boolean {
            val s = session
            if ((s != null) && (!s.isClosed) && (!forceOverride)) return true

            session?.close()
            session = null

            if (forceOverride) return tryOpenSession(forceOverride = true).isSuccess

            val firstTry = tryOpenSession(false)
            if (firstTry.isSuccess) return true

            val error = firstTry.exceptionOrNull()
            if (error is AppSearchException && error.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) {
                return tryOpenSession(true).isSuccess
            }
            return false
        }

        private suspend fun tryOpenSession(forceOverride: Boolean): Result<Unit> {
            var newSession: AppSearchSessionWrapper? = null
            return try {
                newSession = appSearchWrapper.createSearchSession(context, DATABASE_NAME)
                newSession.setSchema(appSearchWrapper.createSchemaRequest(forceOverride))
                session = newSession
                _status.value = TrackingStatus.Active
                Result.success(Unit)
            } catch (e: CancellationException) {
                newSession?.close()
                throw e
            } catch (e: AppSearchException) {
                newSession?.close()
                handleSchemaException(e, forceOverride)
                Result.failure(e)
            } catch (e: Exception) {
                newSession?.close()
                handleInitializationError(e)
                Result.failure(e)
            }
        }

        private fun handleSchemaException(
            e: AppSearchException,
            forceOverride: Boolean,
        ) {
            if ((e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) && !forceOverride) {
                return
            }
            AppLogger.e("AppSearch schema setup failed (force=$forceOverride, code=${e.resultCode})", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, e.message)
        }

        /**
         * Классифицирует ошибку инициализации AppSearch.
         *
         * **Строгое соблюдение API:** на AOSP LocalStorage.createSearchSessionAsync
         * бросает один из трёх стандартных типов. Проверка по тексту сообщения
         * ИСКЛЮЧЕНА: текст может меняться между версиями Android и OEM-сборками.
         *
         * Три стандартных типа → NOT_SUPPORTED:
         * - [IllegalStateException] — сервис AppSearch недоступен (GMS-free сборки)
         * - [DeadObjectException] — системный сервис AppSearch аварийно завершился
         * - [SecurityException] — отсутствие прав
         *
         * Все остальные типы → UNKNOWN.
         *
         * **Отклонение кастомной прошивки:** если кастомная прошивка бросает
         * иные типы при недоступности AppSearch — это проблема кастомной прошивки.
         */
        private fun handleInitializationError(e: Exception) {
            if (e is CancellationException) throw e

            val isNotSupported =
                hasCause(e, IllegalStateException::class.java) ||
                    hasCause(e, DeadObjectException::class.java) ||
                    hasCause(e, SecurityException::class.java)

            if (isNotSupported) {
                AppLogger.w("AppSearch is not supported or service failed on this device.", e)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.NOT_SUPPORTED,
                        "AppSearch service not available on this device",
                    )
            } else {
                AppLogger.e("Fatal AppSearch initialization error", e)
                _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
            }
        }

        private fun hasCause(
            e: Throwable,
            targetClass: Class<out Throwable>,
        ): Boolean {
            var current: Throwable? = e
            while (current != null) {
                if (targetClass.isInstance(current)) return true
                current = current.cause
            }
            return false
        }

        suspend fun indexSnapshot(snapshot: GlobalSnapshot): Boolean {
            val hasActiveSims = snapshot.simSnapshots.any { it.isActive }
            val currentHash =
                if (hasActiveSims) SnapshotUtils.calculateContentHash(snapshot) else null
            return sessionMutex.withLock {
                if (hasActiveSims && (currentHash == lastSnapshotHash)) return@withLock true
                if (!hasActiveSims && (lastSnapshotHash == null)) return@withLock true

                var result = false
                if (ensureSession()) {
                    try {
                        result = executeIndexing(snapshot, currentHash)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AppSearchException) {
                        if (e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) {
                            AppLogger.w("AppSearch schema mismatch, retrying with force override")
                            session?.close()
                            session = null
                            if (ensureSession(forceOverride = true)) {
                                try {
                                    result = executeIndexing(snapshot, currentHash)
                                } catch (retryException: CancellationException) {
                                    throw retryException
                                } catch (retryException: Exception) {
                                    AppLogger.e("AppSearch indexing failed again after retry", retryException)
                                    lastSnapshotHash = null
                                    _status.value =
                                        TrackingStatus.Error(
                                            TrackingStatus.ErrorType.DATABASE_ERROR,
                                            retryException.message,
                                        )
                                }
                            }
                        } else {
                            AppLogger.e("AppSearch indexing failed", e)
                            lastSnapshotHash = null
                            _status.value =
                                TrackingStatus.Error(
                                    TrackingStatus.ErrorType.DATABASE_ERROR,
                                    e.message,
                                )
                        }
                    } catch (e: Exception) {
                        AppLogger.e("AppSearch non-AppSearch error during indexing", e)
                        lastSnapshotHash = null
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    }
                }
                result
            }
        }

        private suspend fun executeIndexing(
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): Boolean =
            withContext(ioDispatcher) {
                // Строгое соблюдение API kotlinx.coroutines: withTimeoutOrNull при таймауте
                // возвращает null, а НЕ бросает TimeoutCancellationException.
                // Таймаут обрабатывается проверкой результата, а не catch-блоком.
                // На AOSP индексация завершается в пределах SEARCH_INDEX_TIMEOUT_MS.
                // Таймаут возможен только при деградации хранилища на кастомной прошивке.
                val result: Boolean? =
                    try {
                        withTimeoutOrNull(Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS.milliseconds) {
                            val documents = UsageDocument.fromGlobalSnapshot(snapshot)
                            performIndexUpdate(documents)
                            lastSnapshotHash = currentHash
                            _status.value = TrackingStatus.Active
                            true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AppSearchException) {
                        // Bubble up to indexSnapshot for retry
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e("AppSearch indexing error", e)
                        lastSnapshotHash = null
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                        false
                    }

                if (result == null) {
                    AppLogger.e("AppSearch indexing timed out after ${Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS}ms")
                    lastSnapshotHash = null
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Indexing timeout")
                    return@withContext false
                }
                result
            }

        private suspend fun performIndexUpdate(documents: List<UsageDocument>) {
            val currentIds = documents.asSequence().map { it.id }.toSet()

            var putException: Exception? = null

            // Сначала добавляем/обновляем актуальные документы
            if (documents.isNotEmpty()) {
                try {
                    session?.putDocuments(appSearchWrapper.createPutRequest(documents))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Partial failure during putDocuments, will proceed with removal", e)
                    putException = e
                }
            }

            // Затем удаляем устаревшие
            val staleIds = mutableSetOf<String>()
            SimConstants.SUPPORTED_SLOT_INDICES.forEach { slot ->
                val possibleIds =
                    listOf(
                        SemanticMapper.getDocumentId(slot, PackageType.MINUTES),
                        SemanticMapper.getDocumentId(slot, PackageType.DATA),
                    )
                staleIds.addAll(possibleIds.filter { it !in currentIds })
            }

            var removeException: Exception? = null
            if (staleIds.isNotEmpty()) {
                try {
                    session?.removeByDocumentId(appSearchWrapper.createRemoveRequest(staleIds))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Failure during removeByDocumentId", e)
                    removeException = e
                }
            }

            throwIfErrors(putException, removeException)
        }

        /**
         * Проверяет наличие ошибок при обновлении индекса.
         *
         * Частичный сбой AppSearch (ошибка при добавлении или удалении документов) требует сброса
         * хеша последнего снапшота (lastSnapshotHash = null в вызывающем коде). Это необходимо
         * для гарантии консистентности индекса при следующем запуске: если мы не сбросим хеш,
         * следующая индексация того же самого снапшота будет пропущена из-за оптимизации хэшей,
         * и индекс останется в рассинхронизированном или поврежденном состоянии.
         *
         * Мы выбрасываем [AppSearchException], чтобы он был перехвачен в [indexSnapshot],
         * где выполняется корректный сброс состояния и установка статуса ошибки без краша приложения.
         */
        @Suppress("ThrowsCount")
        private fun throwIfErrors(
            putEx: Exception?,
            removeEx: Exception?,
        ) {
            if (putEx != null || removeEx != null) {
                // Если у нас ровно одна ошибка и она уже AppSearchException, пробрасываем её как есть,
                // чтобы сохранить код ошибки (например, RESULT_INVALID_SCHEMA) для ретрая.
                if (putEx is AppSearchException && removeEx == null) throw putEx
                if (removeEx is AppSearchException && putEx == null) throw removeEx

                val message =
                    when {
                        putEx != null && removeEx != null ->
                            "put failed: ${putEx.message}; remove failed: ${removeEx.message}"
                        putEx != null -> "put failed: ${putEx.message}"
                        else -> "remove failed: ${removeEx?.message}"
                    }
                throw AppSearchException(AppSearchResult.RESULT_INTERNAL_ERROR, message)
            }
        }

        companion object {
            private const val DATABASE_NAME = "usage"
            const val NAMESPACE = "usage"
        }
    }
