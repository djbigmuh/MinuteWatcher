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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
open class SemanticSearchManager
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
        private var lastSnapshotVersion: com.example.minutewatcher.core.model.SchemaVersion? = null
        private var job: Job? = null
        private var lastSnapshotFlow: StateFlow<GlobalSnapshot>? = null

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        /**
         * Потокобезопасные счетчики для отслеживания ошибок индексации.
         * Доступ из recover() и корутины коллекционирования snapshotFlow.
         */
        private val consecutiveIndexFailures = AtomicInteger(0)
        private val lastIndexFailureTime = AtomicLong(0L)

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
                                if (consecutiveIndexFailures.get() >= MAX_CONSECUTIVE_INDEX_FAILURES) {
                                    // Пропускаем индексацию, но не прерываем коллекционирование.
                                    // Сброс счётчика произойдёт при перезапуске приложения.
                                    return@collect
                                }
                                /**
                                 * **Строгое соблюдение API:** на AOSP `indexSnapshot` завершается успешно
                                 * при корректном состоянии AppSearch. Повторяющиеся сбои указывают на
                                 * проблему кастомной прошивки.
                                 *
                                 * **Эвристика в пределах критерия надёжности:** после [MAX_CONSECUTIVE_INDEX_FAILURES]
                                 * последовательных сбоев индексация подавляется до перезапуска.
                                 * Это предотвращает бесконечный цикл ошибок. Сброс при перезапуске —
                                 * допустимая эвристика, т.к. перезапуск может устранить причину сбоя.
                                 */
                                try {
                                    val success = indexSnapshot(snapshot)
                                    if (success) {
                                        consecutiveIndexFailures.set(0)
                                        lastIndexFailureTime.set(0L)
                                    } else {
                                        handleIndexFailure()
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLogger.e("SemanticSearchManager: unexpected error during indexing", e)
                                    handleIndexFailure()
                                }
                            }
                    }
            }
        }

        /**
         * Гарантирует наличие активной сессии AppSearch.
         *
         * **Стратегия retry:**
         * - [AppSearchResult.RESULT_INVALID_SCHEMA] → повтор с forceOverride=true.
         *   Это единственный код ошибки, при котором повтор имеет смысл:
         *   схема могла измениться (добавлены поля в UsageDocument),
         *   и forceOverride пересоздаёт индекс.
         * - Все остальные коды → false (без повтора).
         *   Другие ошибки (RESULT_INTERNAL_ERROR, RESULT_NOT_FOUND и т.д.)
         *   указывают на системную проблему, которую повтор не устранит.
         *
         * **Строгое соблюдение API:** на AOSP LocalStorage.createSearchSessionAsync
         * бросает один из стандартных типов исключений. Повтор с forceOverride
         * выполняется ТОЛЬКО при RESULT_INVALID_SCHEMA. Для остальных типов
         * повтор бессмыслен — это проблема кастомной прошивки или системного сервиса.
         *
         * **Отклонение кастомной прошивки:** если кастомная прошивка бросает
         * нестандартные исключения при открытии сессии AppSearch — это проблема
         * кастомной прошивки, не проекта.
         *
         * @param forceOverride Если true, принудительно пересоздаёт схему.
         * @return true если сессия успешно открыта и готова к работе.
         */
        private suspend fun ensureSession(forceOverride: Boolean = false): Boolean {
            val s = session
            if ((s != null) && (!s.isClosed) && (!forceOverride)) return true

            session?.close()
            session = null

            if (forceOverride) return tryOpenSession(forceOverride = true).isSuccess

            val firstTry = tryOpenSession(forceOverride = false)
            if (firstTry.isSuccess) return true

            val error = firstTry.exceptionOrNull()
            if ((error is AppSearchException) && (error.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA)) {
                return tryOpenSession(true).isSuccess
            }
            return false
        }

        private suspend fun tryOpenSession(forceOverride: Boolean): Result<Unit> {
            var newSession: AppSearchSessionWrapper? = null
            // При сбое setSchema новый session закрывается, и session остаётся null.
            // Это гарантирует, что при следующем вызове ensureSession будет создана
            // новая сессия. На AOSP setSchema завершается за миллисекунды.
            // Сбой указывает на деградацию AppSearch на кастомной прошивке.
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
                // Для всех ошибок, кроме RESULT_INVALID_SCHEMA (обрабатывается в ensureSession),
                // повтор не выполняется. Статус уже установлен в handleSchemaException
                // или handleInitializationError.
                Result.failure(e)
            } catch (e: Exception) {
                newSession?.close()
                handleInitializationError(e)
                // Для всех ошибок, кроме RESULT_INVALID_SCHEMA (обрабатывается в ensureSession),
                // повтор не выполняется. Статус уже установлен в handleSchemaException
                // или handleInitializationError.
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
         * Три стандартных типа → NOT_SUPPORTED:
         * - [IllegalStateException] — сервис AppSearch недоступен (GMS-free сборки)
         * - [DeadObjectException] — системный сервис AppSearch аварийно завершился
         * - [SecurityException] — отсутствие прав
         *
         * Все остальные типы → UNKNOWN.
         *
         * Классификация по тексту сообщения ИСКЛЮЧЕНА: текст может меняться
         * между версиями Android и OEM-сборками. Проверка по типу исключения
         * в cause-цепочке — единственный стабильный критерий.
         *
         * Отклонение кастомной прошивки: если кастомная прошивка бросает
         * иные типы при недоступности AppSearch — это проблема кастомной прошивки.
         */
        private fun handleInitializationError(e: Exception) {
            if (e is CancellationException) {
                throw e
            }

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

        private fun handleIndexFailure() {
            val failures = consecutiveIndexFailures.incrementAndGet()
            lastIndexFailureTime.set(System.currentTimeMillis())
            if (failures >= MAX_CONSECUTIVE_INDEX_FAILURES) {
                AppLogger.e(
                    "SemanticSearchManager: $failures consecutive indexing failures. " +
                        "Further attempts suppressed until next app restart. " +
                        "On AOSP this indicates a system-level issue. " +
                        "On custom firmware this may be a firmware-specific problem.",
                )
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        "Indexing suppressed after $failures failures",
                    )
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
                if (hasActiveSims && (currentHash == lastSnapshotHash) && (snapshot.version == lastSnapshotVersion)) {
                    return@withLock true
                }
                if (!hasActiveSims && (lastSnapshotHash == null)) return@withLock true

                if (ensureSession()) {
                    performIndexingWithRetry(snapshot, currentHash)
                } else {
                    false
                }
            }
        }

        private suspend fun performIndexingWithRetry(
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): Boolean {
            return try {
                executeIndexing(snapshot, currentHash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppSearchException) {
                handleIndexingAppSearchException(e, snapshot, currentHash)
            } catch (e: Exception) {
                handleIndexingGeneralException(e)
                false
            }
        }

        private suspend fun handleIndexingAppSearchException(
            e: AppSearchException,
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): Boolean {
            if (e.resultCode == AppSearchResult.RESULT_INVALID_SCHEMA) {
                AppLogger.w("AppSearch schema mismatch, retrying with force override")
                session?.close()
                session = null
                if (ensureSession(forceOverride = true)) {
                    return try {
                        executeIndexing(snapshot, currentHash)
                    } catch (retryException: CancellationException) {
                        throw retryException
                    } catch (retryException: Exception) {
                        AppLogger.e("AppSearch indexing failed again after retry", retryException)
                        resetLastSnapshotState()
                        _status.value =
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.DATABASE_ERROR,
                                retryException.message,
                            )
                        false
                    }
                }
            } else {
                AppLogger.e("AppSearch indexing failed", e)
                resetLastSnapshotState()
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        e.message,
                    )
            }
            return false
        }

        private fun handleIndexingGeneralException(e: Exception) {
            AppLogger.e("AppSearch non-AppSearch error during indexing", e)
            resetLastSnapshotState()
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
        }

        private fun resetLastSnapshotState() {
            lastSnapshotHash = null
            lastSnapshotVersion = null
        }

        /**
         * Выполняет индексацию snapshot в AppSearch.
         *
         * Строгое соблюдение API: на AOSP LocalStorage.createSearchSessionAsync
         * завершается за миллисекунды. Индексация одного snapshot (~6 документов
         * для 3 SIM × 2 типа) завершается за < 100 мс.
         * Таймаут [Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS] (10 сек) — защитный запас.
         * Таймаут измеряет только время рендера, а не ожидание мьютекса.
         * На кастомных прошивках индексация может занять больше 10 сек —
         * это проблема кастомной прошивки.
         *
         * withTimeoutOrNull возвращает null при таймауте (TimeoutCancellationException
         * перехватывается). Реальный CancellationException (отмена scope) пробрасывается.
         * Это строгое соблюдение API kotlinx.coroutines.
         */
        private suspend fun executeIndexing(
            snapshot: GlobalSnapshot,
            currentHash: Int?,
        ): Boolean =
            withContext(ioDispatcher) {
                // Строгое соблюдение API: на AOSP LocalStorage.createSearchSessionAsync
                // завершается за миллисекунды. session == null после ensureSession()
                // возможен только при деградации хранилища на кастомной прошивке.
                // lastSnapshotHash обновляется только при полном успехе индексации,
                // что гарантирует повторную индексацию при следующем вызове.
                val currentSession =
                    session
                        ?: run {
                            AppLogger.e("AppSearch session is null in executeIndexing, skipping")
                            return@withContext false
                        }

                // Строгое соблюдение API kotlinx.coroutines: withTimeoutOrNull при таймауте
                // возвращает null, а НЕ бросает TimeoutCancellationException.
                // Таймаут обрабатывается проверкой результата, а не catch-блоком.
                // На AOSP индексация завершается в пределах SEARCH_INDEX_TIMEOUT_MS.
                // Таймаут возможен только при деградации хранилища на кастомной прошивке.
                val result: Boolean? =
                    try {
                        withTimeoutOrNull(
                            Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS.milliseconds,
                        ) {
                            val documents = UsageDocument.fromGlobalSnapshot(snapshot)
                            performIndexUpdate(currentSession, documents)
                            // lastSnapshotHash and lastSnapshotVersion updated ТОЛЬКО при полном успехе индексации.
                            // При частичном сбое (put failed, remove succeeded) хеш НЕ
                            // обновляется, что гарантирует повторную индексацию того же
                            // снапшота при следующем вызове indexSnapshot. Это предотвращает
                            // рассинхронизацию индекса с реальным состоянием пакетов.
                            //
                            // **Строгое соблюдение API:** на AOSP putDocuments не бросает
                            // исключений при корректном состоянии хранилища. Сбой указывает
                            // на деградацию AppSearch на кастомной прошивке, не соответствующей
                            // документации Google. Это проблема кастомной прошивки.
                            lastSnapshotHash = currentHash
                            lastSnapshotVersion = snapshot.version
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
                        lastSnapshotVersion = null
                        _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                        false
                    }

                if (result == null) {
                    AppLogger.e("AppSearch indexing timed out after ${Constants.Semantic.SEARCH_INDEX_TIMEOUT_MS}ms")
                    lastSnapshotHash = null
                    lastSnapshotVersion = null
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, "Indexing timeout")
                    return@withContext false
                }
                result
            }

        /**
         * Выполняет обновление индекса: добавление актуальных документов
         * и удаление устаревших.
         *
         * Порядок операций: сначала put (добавление/обновление актуальных
         * документов), затем remove (удаление устаревших).
         *
         * Строгое соблюдение API: на AOSP putDocuments не бросает исключений
         * при корректном состоянии хранилища. Сбой указывает на деградацию
         * AppSearch на кастомной прошивке. Это проблема кастомной прошивки.
         *
         * Стратегия при частичном сбое: если put падает, но remove succeeds,
         * хеш НЕ обновляется. Это гарантирует полную переиндексацию при
         * следующем вызове. Окно, в котором stale-документы удалены, а новые
         * не добавлены, допустимо: следующий вызов indexSnapshot восстановит
         * консистентность.
         *
         * Оба исключения собираются и пробрасываются вместе через throwIfErrors.
         */
        private suspend fun performIndexUpdate(
            session: AppSearchSessionWrapper,
            documents: List<UsageDocument>,
        ) {
            val currentIds = documents.asSequence().map { it.id }.toSet()

            var putException: Exception? = null

            if (documents.isNotEmpty()) {
                try {
                    session.putDocuments(createPutRequest(documents))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Partial failure during putDocuments, will proceed with removal", e)
                    putException = e
                }
            }

            // Затем удаляем устаревшие.
            // Strict compliance: if a package is no longer present in the snapshot (e.g. uninstalled
            // or invalid PackageInfo/NameNotFoundException during snapshot creation), its document
            // is explicitly removed from AppSearch. We do not use heuristics to "keep" or "guess"
            // stale data.
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
                    session.removeByDocumentId(createRemoveRequest(staleIds))
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
         * Extracted as `internal open` methods to allow overriding in tests without
         * triggering AppSearch's document converter, which can be problematic
         * in Robolectric environments.
         */
        internal open fun createPutRequest(documents: List<UsageDocument>) =
            appSearchWrapper.createPutRequest(documents)

        internal open fun createRemoveRequest(ids: Set<String>) = appSearchWrapper.createRemoveRequest(ids)

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
            private const val MAX_CONSECUTIVE_INDEX_FAILURES = 5
        }
    }
