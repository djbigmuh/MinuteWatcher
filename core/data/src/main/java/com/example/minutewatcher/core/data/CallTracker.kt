package com.example.minutewatcher.core.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.TrackingStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

private class DataStoreTimeoutException(
    message: String,
) : Exception(message)

/**
 * CallTracker is responsible for capturing call start/end events and attributing duration to SIM identities.
 *
 * **Consistency Contract:**
 * The internal state and exposed [status] of CallTracker are guaranteed to be consistent after returning
 * from any public method (onCallStarted, onCallEnded, onBoot, recover).
 * Guaranteed synchronization is achieved using try-finally blocks to refresh status even if
 * exceptions occur during critical section execution.
 */
@Suppress("LargeClass")
@Singleton
open class CallTracker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val simIdentityRepository: SimIdentityRepository,
        private val simUsageRepository: SimUsageRepository,
        private val appSettingsRepository: AppSettingsRepository,
        private val callSessionRepository: CallSessionRepository,
        private val dataChangeNotifier: DataChangeNotifier,
        private val permissionManager: PermissionManager,
        private val telephonyManager: TelephonyManager,
        private val timeProvider: TimeProvider,
    ) : TrackingStatusProvider {
        override val key: String = "calls"

        private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
        override val status: StateFlow<TrackingStatus> = _status.asStateFlow()

        // Все обращения к writeAttempts и attributionFailures происходят
        // строго внутри callMutex.withLock, что гарантирует последовательный
        // доступ. ConcurrentHashMap не требуется.
        private val writeAttempts = HashMap<Int, Int>()

        private val attributionFailures = HashMap<Int, Int>()

        /**
         * Мьютекс сериализует обработку событий звонков (старт, завершение, boot).
         * Удерживается на время чтения и записи в DataStore.
         *
         * Необходимость: без мьютекса два события OFFHOOK для разных subId могут
         * прочитать/записать пересекающиеся ключи DataStore и повредить состояние
         * сессии. Мьютекс гарантирует атомарность операции "чтение → проверка →
         * запись" для каждого события.
         *
         * **Контракт AOSP:** на AOSP DataStore отвечает < 100 мс.
         *
         * **Отклонение кастомной прошивки:** На кастомных прошивках с деградацией хранилища
         * время удержания мьютекса может увеличиться, что приведёт к задержке обработки
         * следующего события звонка. Это проблема кастомной прошивки.
         *
         * **Стратегия приложения:** мьютекс необходим для корректности; задержка при деградации
         * хранилища предпочтительнее повреждения данных.
         */
        private val callMutex = Mutex()

        private val callEndResolver =
            CallEndResolver(
                context = context,
                callSessionRepository = callSessionRepository,
                permissionManager = permissionManager,
                telephonyManager = telephonyManager,
                dataChangeNotifier = dataChangeNotifier,
                endCall = { subId, notify -> endCall(subId, notify) },
                setError = { _status.value = it },
            )

        private suspend fun isCallTrackingEnabled(): Boolean {
            val settings =
                readWithRetry(
                    label = "settings for call tracking check",
                    attempts = READ_RETRY_ATTEMPTS,
                    timeoutMs = Constants.Flow.CALL_SESSION_READ_TIMEOUT_MS,
                ) {
                    appSettingsRepository.getAppSettings()
                }.getOrNull()

            if (settings == null) {
                AppLogger.e("Failed to read settings for call tracking check, assuming disabled for safety")
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Settings DataStore read timeout",
                    )
                return false
            }
            return settings.callTrackingEnabled
        }

        override suspend fun recover() {
            callMutex.withLock {
                try {
                    _status.value = TrackingStatus.Idle
                    checkPermissions()
                } finally {
                    withContext(NonCancellable) {
                        refreshStatus(force = true)
                    }
                }
            }
        }

        /**
         * Called on device boot to clean up any stale state.
         */
        suspend fun onBoot() {
            callMutex.withLock {
                try {
                    val activeSessions = callSessionRepository.getActiveCallSubIds()
                    callSessionRepository.clearAllCallSessions()
                    _status.value = TrackingStatus.Idle
                    // Очистка мап попыток: после перезагрузки все предыдущие сессии неактуальны.
                    // Строгое соблюдение API: на AOSP onBoot вызывается один раз при загрузке;
                    // мапы не должны содержать записей от предыдущего сеанса работы устройства.
                    writeAttempts.clear()
                    attributionFailures.clear()
                    AppLogger.d("Cleared ${activeSessions.size} stale call sessions on boot")
                } finally {
                    // refreshStatus() вызывается после очистки сессий для обеспечения
                    // консистентности между состоянием в памяти и данными в DataStore.
                    // На AOSP clearAllCallSessions() завершается синхронно.
                    // На кастомных прошивках с деградацией DataStore записи могут
                    // остаться — это проблема кастомной прошивки.
                    withContext(NonCancellable) {
                        refreshStatus()
                    }
                }
            }
        }

        /**
         * Вызывается при получении ACTION_PHONE_STATE_CHANGED с EXTRA_STATE_OFFHOOK.
         *
         * Стратегия обработки исключений:
         * - [SecurityException]: обрабатывается отдельно, устанавливает
         *   TrackingStatus.ErrorType.PERMISSION_MISSING.
         * - [CancellationException]: пробрасывается для кооперативной отмены.
         * - Все остальные исключения: логируются, устанавливается
         *   TrackingStatus.ErrorType.UNKNOWN.
         *
         * Строгое соблюдение API: на AOSP handleCallStartInternal не бросает
         * исключений при корректном состоянии DataStore. Сбой указывает на
         * деградацию хранилища на кастомной прошивке. Это проблема кастомной прошивки.
         *
         * В блоке finally вызывается refreshStatus() для актуализации статуса.
         * NonCancellable гарантирует выполнение даже при отмене корутины.
         */
        suspend fun onCallStarted(subId: Int) {
            callMutex.withLock {
                try {
                    if (!isCallTrackingEnabled()) {
                        AppLogger.d("Call tracking is disabled in settings, ignoring onCallStarted")
                        return@withLock
                    }
                    if (!checkPermissions()) {
                        return@withLock
                    }

                    val effectiveSubId = resolveEffectiveSubId(subId)
                    if (effectiveSubId == -1) return@withLock

                    val isNewSessionStarted = handleCallStartInternal(effectiveSubId)
                    if (isNewSessionStarted) {
                        _status.value = TrackingStatus.Active
                    }
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in onCallStarted", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                } finally {
                    // NonCancellable гарантирует, что refreshStatus выполнится даже при отмене
                    // корутины, чтобы статус был актуальным. Это строгое соблюдение API:
                    // на AOSP getActiveCallSubIds() не бросает исключений.
                    withContext(NonCancellable) {
                        try {
                            refreshStatus()
                        } catch (e: CancellationException) {
                            // Подавление ThrowingExceptionFromFinally оставлено намеренно: в Kotlin Coroutines
                            // проброс CancellationException из finally блока является стандартным способом
                            // обеспечения корректной остановки иерархии корутин при отмене.
                            @Suppress("ThrowingExceptionFromFinally")
                            throw e
                        } catch (e: Exception) {
                            // Логирование без проброса: ошибка refreshStatus не должна
                            // маскировать оригинальное исключение из onCallStarted.
                            // На AOSP refreshStatus не бросает исключений.
                            // Отклонение кастомной прошивки: если DataStore деградирует —
                            // это проблема кастомной прошивки.
                            AppLogger.w("CallTracker: refreshStatus failed in onCallStarted finally block", e)
                        }
                    }
                }
            }
        }

        private fun resolveEffectiveSubId(subId: Int): Int {
            val effective =
                if (subId == -1) {
                    permissionManager.getDefaultVoiceSubscriptionId()
                } else {
                    subId
                }

            if (effective == -1) {
                AppLogger.d("Call started with unknown subId and no default voice subId, ignoring")
            }
            return effective
        }

        /**
         * Читает данные с retry и timeout.
         * Обрабатывает два типа сбоев:
         * 1. Таймаут — Flow не эмитит значение в пределах timeoutMs.
         * 2. Внутреннее исключение Flow — DataStore в повреждённом состоянии.
         * В обоих случаях выполняется retry с экспоненциальной задержкой.
         *
         * **Стратегия повторных попыток:**
         * Выполняется до [attempts] попыток с экспоненциальной задержкой (начало: [retryDelayMs],
         * удвоение на каждом шаге). Это защитный механизм, позволяющий пережить
         * кратковременную деградацию хранилища без потери данных звонка.
         *
         * **Строгое соблюдение API:** на AOSP DataStore отвечает < 100 мс. Таймаут возможен
         * только при деградации хранилища на кастомной прошивке. Это проблема
         * кастомной прошивки, не проекта.
         *
         * **Стратегия при исчерпании попыток:** возвращается [Result.failure]. Вызывающий код
         * ОБЯЗАН решить, как поступить (например, очистить сессию или пробросить ошибку).
         *
         * **Эвристика в пределах критерия надёжности:** повторные попытки и ограничение их
         * количества (cap) балансируют между сохранением данных и предотвращением
         * бесконечного ожидания в критических секциях.
         */
        @Suppress("LongParameterList")
        // Подавление LongParameterList оправдано: readWithRetry — это универсальный инфраструктурный метод,
        // где каждый из 5 параметров (label, attempts, timeout, delay, reader) необходим для точной
        // настройки стратегии повторов в различных сценариях деградации DataStore.
        private suspend fun <T : Any> readWithRetry(
            label: String,
            attempts: Int = READ_RETRY_ATTEMPTS,
            timeoutMs: Long = Constants.Flow.SHORT_TIMEOUT_MS,
            retryDelayMs: Long = READ_RETRY_DELAY_MS,
            reader: () -> Flow<T>,
        ): Result<T> {
            var currentDelay = retryDelayMs
            var lastException: Exception? = null
            for (attempt in 1..attempts) {
                val result =
                    withTimeoutOrNull(timeoutMs.milliseconds) {
                        try {
                            reader().first()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.w("readWithRetry($label): flow threw on attempt $attempt", e)
                            lastException = e
                            null
                        }
                    }
                result?.let { return Result.success(it) }
                if (attempt < attempts) {
                    AppLogger.d("readWithRetry($label): attempt $attempt failed, retrying in ${currentDelay}ms")
                    delay(currentDelay.milliseconds)
                    currentDelay *= 2
                }
            }
            val errorMessage = lastException?.message ?: "Timeout reading $label"
            AppLogger.w("readWithRetry($label): all $attempts attempts failed ($errorMessage)")
            return Result.failure(DataStoreTimeoutException(errorMessage))
        }

        // Подавление LongMethod и ReturnCount оправдано: метод инкапсулирует атомарную логику захвата
        // начала звонка, включая проверку разрешений, детекцию устаревших сессий и очистку стейта.
        // Разделение метода нарушит линейность восприятия критической секции старта сессии.
        private suspend fun handleCallStartInternal(subId: Int): Boolean {
            val result =
                readWithRetry(
                    label = "session data for subId $subId",
                    attempts = READ_RETRY_ATTEMPTS,
                    timeoutMs = Constants.Flow.CALL_SESSION_READ_TIMEOUT_MS,
                    retryDelayMs = READ_RETRY_DELAY_MS,
                ) {
                    combine(
                        callSessionRepository.getCallStartTime(subId),
                        callSessionRepository.getBootTime(subId),
                    ) { start, boot -> start to boot }
                }

            if (result.isFailure) {
                val errorMessage = result.exceptionOrNull()?.message ?: "Unknown error"
                AppLogger.e("Call start event dropped due to DataStore error for subId $subId: $errorMessage", null)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        errorMessage,
                    )
                withContext(NonCancellable) {
                    try {
                        // При таймауте чтения сессии выполняем очистку сессии для безопасности.
                        // Если очистка тоже падает, сохраняем исходную ошибку таймаута.
                        // На AOSP clearSession не падает при корректном состоянии DataStore.
                        // Двойной сбой (read + clear) возможен только при деградации DataStore
                        // на кастомной прошивке. Это проблема прошивки.
                        clearSession(subId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w(
                            "Failed to clear session for subId $subId after start timeout. " +
                                "Original error: $errorMessage",
                            e,
                        )
                        // Статус уже установлен выше. Не перезаписываем его ошибкой очистки,
                        // чтобы пользователь видел реальную причину (таймаут чтения).
                    }
                }
                return false
            }

            val (startTime, savedBootTime) = result.getOrThrow()
            val currentBootTime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            val isStale = (startTime == 0L) || (savedBootTime == 0L) || (savedBootTime > currentBootTime)

            if (isStale) {
                if ((startTime != 0L) || (savedBootTime != 0L)) {
                    AppLogger.d("Clearing stale call session for subId $subId")
                    try {
                        clearSession(subId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w(
                            "Failed to clear stale session for subId $subId, will overwrite with new values",
                            e,
                        )
                    }
                }

                val identity = resolveIdentity(subId)
                callSessionRepository.setCallStartTime(subId, timeProvider.now())
                callSessionRepository.setBootTime(subId, currentBootTime)
                callSessionRepository.setUptime(subId, currentUptime)
                callSessionRepository.setCallIdentity(subId, identity)
                AppLogger.d("Call started for subId $subId")
                return true
            } else {
                AppLogger.d("Duplicate OFFHOOK for subId $subId, session already active, skipping")
                return false
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives IDLE.
         */
        suspend fun onCallEnded(subId: Int) {
            callMutex.withLock {
                try {
                    if (!isCallTrackingEnabled()) {
                        // Если трекинг звонков был отключён во время активного звонка,
                        // завершение звонка обрабатывается в режиме "только очистка":
                        // сессия удаляется, но длительность не записывается.
                        // Это предотвращает запись данных за период, когда трекинг был выключен.
                        AppLogger.d("Call tracking disabled, clearing session for subId $subId")
                        if (subId == -1) {
                            callSessionRepository.clearAllCallSessions()
                        } else {
                            clearSession(subId)
                        }
                        return@withLock
                    }

                    executeCallEnded(subId)
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in onCallEnded", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)

                    // G-484: Ensure session is cleared even on unexpected failure to prevent stale state.
                    // We choose to clear the session here instead of re-throwing for a Worker retry
                    // because unexpected exceptions (like logic errors) are often non-transient,
                    // and a "hanging" active session is worse than a single lost call record.
                    if (subId != -1) {
                        withContext(NonCancellable) {
                            try {
                                clearSession(subId)
                            } catch (cleanupEx: Exception) {
                                AppLogger.w("CallTracker: Failed to clear session after onCallEnded error", cleanupEx)
                            }
                        }
                    }
                } finally {
                    // NonCancellable гарантирует, что refreshStatus выполнится даже при отмене
                    // корутины, чтобы статус был актуальным. Это строгое соблюдение API:
                    // на AOSP getActiveCallSubIds() не бросает исключений.
                    withContext(NonCancellable) {
                        try {
                            refreshStatus()
                        } catch (e: CancellationException) {
                            // Проброс CancellationException для корректного завершения CoroutineScope.
                            @Suppress("ThrowingExceptionFromFinally")
                            throw e
                        } catch (e: Exception) {
                            // Логирование без проброса: ошибка refreshStatus не должна
                            // маскировать оригинальное исключение из onCallEnded.
                            // На AOSP refreshStatus не бросает исключений.
                            // Отклонение кастомной прошивки: если DataStore деградирует —
                            // это проблема кастомной прошивки.
                            AppLogger.w("CallTracker: refreshStatus failed in onCallEnded finally block", e)
                        }
                    }
                }
            }
        }

        private suspend fun executeCallEnded(subId: Int) {
            if (!checkPermissions()) return

            if (subId == -1) {
                try {
                    /**
                     * Обработка завершения звонка при subId == -1.
                     *
                     * **Строгое соблюдение API:** на AOSP subId всегда известен при наличии
                     * READ_PHONE_STATE. subId == -1 возможен только на кастомных прошивках,
                     * не передающих subscription ID в intent. Это проблема кастомной прошивки.
                     *
                     * **Эвристика в пределах критерия надёжности:**
                     * - Если все активные сессии в IDLE → закрыть все (однозначно).
                     * - Если ровно одна сессия в IDLE → закрыть её (однозначно).
                     * - Если неоднозначно → НЕ закрывать ничего, установить Error.
                     *   Эвристики «закрыть самую старую» или «закрыть default voice»
                     *   намеренно исключены: они могут привести к потере данных
                     *   при одновременных звонках на dual-SIM.
                     */
                    callEndResolver.resolveUnknownSubIdEnd()
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                }
            } else {
                endCall(subId)
            }
        }

        private fun checkPermissions(): Boolean {
            val error =
                when {
                    !permissionManager.isTelephonySupported() -> {
                        AppLogger.w("CallTracker: telephony not supported")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.UNKNOWN,
                            "SubscriptionManager unavailable",
                        )
                    }

                    !permissionManager.hasPhoneStatePermission() -> {
                        AppLogger.w("CallTracker: phone state permission missing")
                        TrackingStatus.Error(
                            TrackingStatus.ErrorType.PERMISSION_MISSING,
                            "Phone State missing",
                        )
                    }

                    else -> null
                }

            if (error != null) {
                _status.value = error
            } else if (_status.value is TrackingStatus.Error) {
                _status.value = TrackingStatus.Idle
            }

            return error == null
        }

        private fun handleSecurityException(e: SecurityException) {
            AppLogger.e("SecurityException in CallTracker", e)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING, e.message)
        }

        /**
         * Ends the call for the given [subId].
         *
         * При невозможности прочитать сессию (таймаут), сессия принудительно очищается.
         *
         * **Контракт AOSP:** на AOSP DataStore отвечает < 100 мс.
         *
         * **Отклонение кастомной прошивки:** Таймаут возможен только при деградации DataStore на кастомных прошивках.
         * Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** Потеря данных одного звонка предпочтительнее полной блокировки трекинга.
         * Сессия принудительно очищается, так как ненулевой startTime блокирует следующий звонок.
         *
         * @param subId The subscription ID.
         * @param notify Whether to notify about data changes.
         */
        private suspend fun endCall(
            subId: Int,
            notify: Boolean = true,
        ) {
            val sessionData = loadCallSessionData(subId)
            if (sessionData == null) {
                handleEndCallTimeout(subId)
                return
            }

            val currentBootTime = timeProvider.elapsedRealtime()
            val currentUptime = timeProvider.uptimeMillis()

            if (sessionData.startTime > 0) {
                processActiveSessionEnd(subId, sessionData, currentBootTime, currentUptime, notify)
            } else {
                // **Контракт AOSP:** на AOSP старт вызова всегда предшествует завершению и фиксируется в OFFHOOK.
                //
                // **Отклонение кастомной прошивки:** Отсутствие сессии при IDLE означает, что старт не был захвачен
                // (приложение не работало, либо прошивка не доставила событие). Это проблема кастомной прошивки.
                //
                // **Стратегия приложения:** случай логируется для наблюдаемости, атрибуция не выполняется.
                AppLogger.w(
                    "Call ended but no active session found for subId=$subId " +
                        "(call start was not captured)",
                    null,
                )
            }
        }

        /**
         * Обрабатывает таймаут чтения данных сессии при завершении звонка.
         *
         * Строгое соблюдение API: на AOSP loadCallSessionData() завершается
         * за миллисекунды. Таймаут указывает на деградацию DataStore
         * на кастомной прошивке. Это проблема кастомной прошивки.
         *
         * Стратегия: сессия принудительно очищается, длительность звонка теряется.
         * Это предпочтительнее, чем оставить сессию «зависшей» и заблокировать
         * трекинг для данного subId навсегда. Потеря данных одного звонка
         * допустима в пределах критерия надёжности.
         *
         * Повторная попытка: при следующем звонке на этом subId будет
         * создана новая сессия.
         */
        private suspend fun handleEndCallTimeout(subId: Int) {
            val message = "Session read timed out for subId $subId"
            AppLogger.e(message, null)
            _status.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.DATABASE_ERROR,
                    message,
                )

            withContext(NonCancellable) {
                // Строгое соблюдение API: на AOSP DataStore.edit не бросает исключений
                // при корректном состоянии хранилища. Таймаут или сбой записи указывает
                // на деградацию DataStore на кастомной прошивке.
                // Стратегия: одна повторная попытка очистки сессии. Если и она не удалась,
                // устанавливаем Error-статус. Потеря данных одного звонка предпочтительнее
                // перманентной блокировки трекинга.
                var lastEx: Exception? = null
                for (attempt in 1..2) {
                    try {
                        clearSession(subId)
                        return@withContext
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastEx = e
                        AppLogger.w("Failed to clear session for subId $subId after timeout (attempt $attempt)", e)
                        if (attempt < 2) delay(500.milliseconds)
                    }
                }

                val cleanupMessage = "Session cleanup failed for subId $subId after timeout"
                AppLogger.e("CallTracker: $cleanupMessage", lastEx)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        cleanupMessage,
                    )
            }
        }

        private suspend fun processActiveSessionEnd(
            subId: Int,
            sessionData: CallSessionData,
            currentBootTime: Long,
            currentUptime: Long,
            notify: Boolean,
        ) {
            val isValid = validateSession(subId, sessionData, currentBootTime)
            if (!isValid) return

            // **Контракт AOSP:** на AOSP монотонное время (elapsedRealtime) гарантирует корректность интервалов.
            //
            // **Отклонение кастомной прошивки:** При изменении пользователем системного времени или после
            // reboot wall-clock может быть некорректным. Это проблема кастомной прошивки.
            //
            // **Стратегия приложения:** проверка „протухшей“ сессии выполняется по монотонному
            // SystemClock.elapsedRealtime().
            val isExpired = (currentBootTime - sessionData.savedBootTime) > Constants.Calls.MAX_CALL_DURATION_MS

            if (isExpired) {
                handleStaleSession(subId)
            } else {
                val durationSeconds = (currentUptime - sessionData.savedUptime) / Constants.MILLIS_PER_SECOND
                if (durationSeconds < 0) {
                    handleNegativeDuration(subId, currentUptime, sessionData.savedUptime)
                } else {
                    performCallAttribution(subId, durationSeconds, sessionData.savedIdentity, notify)
                }
            }
        }

        private suspend fun performCallAttribution(
            subId: Int,
            durationSeconds: Long,
            savedIdentity: String?,
            notify: Boolean,
        ) {
            val success =
                processCallDuration(
                    durationSeconds = durationSeconds.toInt(),
                    subId = subId,
                    savedIdentity = savedIdentity,
                    notify = notify,
                )

            if (success) {
                clearSession(subId)
                writeAttempts.remove(subId)
                attributionFailures.remove(subId)
            } else {
                AppLogger.e(
                    "Call duration write failed for subId $subId (duration: $durationSeconds s), " +
                        "session preserved",
                )
            }
        }

        /**
         * Обрабатывает устаревшую сессию (длительность > MAX_CALL_DURATION_MS).
         *
         * **Контракт AOSP:** на AOSP сессии длительностью > 12 часов не возникают.
         *
         * **Отклонение кастомной прошивки:** Превышение лимита возможно только на кастомных прошивках.
         * Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** сессия не очищается автоматически, статус переводится в Error.
         */
        private fun handleStaleSession(subId: Int) {
            val message = "Call duration exceeded max limit for subId $subId"
            AppLogger.e(message, null)
            _status.value =
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION,
                    message,
                )
        }

        private suspend fun handleNegativeDuration(
            subId: Int,
            currentUptime: Long,
            savedUptime: Long,
        ) {
            AppLogger.w(
                "CallTracker: negative duration for subId $subId " +
                    "(currentUptime=$currentUptime, savedUptime=$savedUptime), clearing session",
            )
            clearSession(subId)
        }

        private data class CallSessionData(
            val startTime: Long,
            val savedBootTime: Long,
            val savedUptime: Long,
            val savedIdentity: String?,
        )

        /**
         * Читает данные сессии звонка с retry и timeout.
         *
         * **Контракт AOSP:** на AOSP DataStore.data эмитит значение в пределах SHORT_TIMEOUT_MS.
         *
         * **Отклонение кастомной прошивки:** Таймаут возможен только при деградации хранилища на кастомных прошивках.
         * Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** Возвращает null при таймауте после всех попыток для принудительной очистки.
         */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        private suspend fun loadCallSessionData(subId: Int): CallSessionData? {
            val result =
                readWithRetry(
                    label = "callSessionData",
                    attempts = READ_RETRY_ATTEMPTS,
                    timeoutMs = Constants.Flow.CALL_SESSION_READ_TIMEOUT_MS,
                    retryDelayMs = READ_RETRY_DELAY_MS,
                ) {
                    combine(
                        callSessionRepository.getCallStartTime(subId),
                        callSessionRepository.getBootTime(subId),
                        callSessionRepository.getUptime(subId),
                        callSessionRepository.getCallIdentity(subId),
                    ) { start, boot, uptime, id ->
                        CallSessionData(start, boot, uptime, id)
                    }
                }

            if (result.isFailure) {
                val errorMessage = result.exceptionOrNull()?.message ?: "Unknown error"
                AppLogger.e("Call session data read failed for subId $subId: $errorMessage", null)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        errorMessage,
                    )
                return null
            }

            return result.getOrThrow()
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        private suspend fun validateSession(
            subId: Int,
            data: CallSessionData,
            currentBootTime: Long,
        ): Boolean {
            val isMissingBootTime = data.savedBootTime == 0L
            val isStaleSession = data.savedBootTime > currentBootTime

            if (isMissingBootTime) {
                AppLogger.w("Call session for subId $subId has no boot time (possible write failure). Discarding.")
            }
            if (isStaleSession) {
                AppLogger.d("Stale call session detected for subId $subId (reboot). Clearing.")
            }

            val isValid = !isMissingBootTime && !isStaleSession
            if (!isValid) {
                clearSession(subId)
            }
            return isValid
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        open suspend fun refreshStatus(force: Boolean = false) {
            val current = _status.value
            val isIgnoredError =
                (current is TrackingStatus.Error) &&
                    (!force) &&
                    (
                        (current.type == TrackingStatus.ErrorType.UNKNOWN) ||
                            (current.type == TrackingStatus.ErrorType.PERMISSION_MISSING) ||
                            (current.type == TrackingStatus.ErrorType.DATABASE_ERROR) ||
                            (current.type == TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION)
                    )

            if (!isIgnoredError) {
                val hasActiveCalls = callSessionRepository.getActiveCallSubIds().isNotEmpty()
                _status.value = if (hasActiveCalls) TrackingStatus.Active else TrackingStatus.Idle
            }
        }

        /**
         * Очищает данные сессии звонка.
         *
         * **Строгое соблюдение API:** на AOSP DataStore.edit не бросает исключений при
         * корректном состоянии хранилища.
         *
         * **Отклонение кастомной прошивки:** исключение возможно только при деградации DataStore
         * на кастомной прошивке, не соответствующей документации Google.
         *
         * **Стратегия приложения:** логируем ошибку через [AppLogger.e], но не пробрасываем её
         * дальше (кроме [CancellationException]). Частичная очистка сессии или лог об ошибке
         * предпочтительнее полной блокировки трекинга из-за необработанного исключения.
         *
         * @param subId Идентификатор подписки, для которой очищается сессия.
         */
        private suspend fun clearSession(subId: Int) {
            withContext(NonCancellable) {
                try {
                    callSessionRepository.setCallStartTime(subId, 0L)
                    callSessionRepository.setBootTime(subId, 0L)
                    callSessionRepository.setUptime(subId, 0L)
                    callSessionRepository.setCallIdentity(subId, null)
                    attributionFailures.remove(subId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // На AOSP DataStore.edit не бросает исключений при корректном
                    // состоянии хранилища. Исключение здесь указывает на деградацию
                    // DataStore на кастомной прошивке.
                    AppLogger.e("CallTracker: failed to fully clear session for subId $subId", e)
                    throw e
                }
            }
        }

        private suspend fun resolveIdentity(subId: Int): String? {
            val subInfoList = permissionManager.getActiveSubscriptions()
            val info = subInfoList.find { it.subscriptionId == subId }
            return info?.let { simIdentityRepository.getIdentity(it.simSlotIndex) }
        }

        /**
         * Processes the completed call duration and attributes it to a SIM identity.
         *
         * Rounding logic:
         * Uses ceiling rounding for minutes (1 sec = 1 min, 61 sec = 2 min).
         * This follows the most common carrier billing practices.
         *
         * **Контракт AOSP:** на AOSP атрибуция всегда успешна при наличии SIM и READ_PHONE_STATE.
         *
         * **Отклонение кастомной прошивки:** Перманентный сбой возможен только на кастомных прошивках,
         * не соответствующих документации Google. Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** Если атрибуция не удаётся [MAX_ATTRIBUTION_FAILURES] раз подряд,
         * сессия принудительно очищается для предотвращения блокировки.
         *
         * @Suppress("ReturnCount") оправдан: метод содержит 3 точки возврата,
         * каждая для отдельного кейса:
         * 1. durationSeconds == 0 → очистка без записи (нулевой вызов)
         * 2. targetIdentity == null → handleAttributionFailure (невозможность атрибуции)
         * 3. targetIdentity != null → writeCallDuration (нормальная запись)
         * Объединение в единую точку возврата потребовало бы вложенных if-else,
         * что снизило бы читаемость критической секции записи данных звонка.
         */
        @Suppress("ReturnCount")
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        private suspend fun processCallDuration(
            durationSeconds: Int,
            subId: Int,
            savedIdentity: String?,
            notify: Boolean = true,
        ): Boolean {
            val (targetIdentity, isFallbackUsed) = resolveTargetIdentity(subId, savedIdentity)

            if (durationSeconds == 0) {
                AppLogger.d("CallTracker: zero-duration call for identity $targetIdentity, clearing session")
                cleanupCounters(subId)
                return true
            }

            val durationMinutes = calculateDurationMinutes(durationSeconds)

            return targetIdentity?.let {
                writeCallDuration(subId, it, durationMinutes, isFallbackUsed, notify)
            } ?: handleAttributionFailure(subId)
        }

        private suspend fun resolveTargetIdentity(
            subId: Int,
            savedIdentity: String?,
        ): Pair<String?, Boolean> {
            var targetIdentity = savedIdentity ?: resolveIdentity(subId)
            var isFallbackUsed = false

            // **Контракт AOSP:** на AOSP subId вызова однозначно известен при наличии READ_PHONE_STATE.
            //
            // **Отклонение кастомной прошивки:** Отсутствие identity при старте вызова возможно только
            // на кастомных прошивках. Это проблема кастомной прошивки, а не приложения.
            //
            // **Стратегия приложения:** Такая атрибуция считается неточной и учитывается в
            // inaccurateCallAttributionsCount.
            if (targetIdentity == null) {
                val defaultVoiceSubId = permissionManager.getDefaultVoiceSubscriptionId()
                if (defaultVoiceSubId != -1) {
                    targetIdentity = resolveIdentity(defaultVoiceSubId)
                    if (targetIdentity != null) {
                        isFallbackUsed = true
                    }
                }
            }
            return targetIdentity to isFallbackUsed
        }

        private fun calculateDurationMinutes(durationSeconds: Int): Int =
            (
                (durationSeconds.toLong() + Constants.SECONDS_PER_MINUTE - 1) /
                    Constants.SECONDS_PER_MINUTE
            ).toInt()

        private suspend fun writeCallDuration(
            subId: Int,
            identity: String,
            durationMinutes: Int,
            isFallbackUsed: Boolean,
            notify: Boolean,
        ): Boolean =
            try {
                simUsageRepository.incrementMinutesUsed(identity, durationMinutes)
                if (isFallbackUsed) {
                    AppLogger.w(
                        "CallTracker: attribution fallback used for subId $subId " +
                            "(identity $identity)",
                        null,
                    )
                    appSettingsRepository.updateAppSettings {
                        it.copy(inaccurateCallAttributionsCount = it.inaccurateCallAttributionsCount + 1)
                    }
                }
                AppLogger.d("Added $durationMinutes min to identity $identity")
                if (notify) {
                    dataChangeNotifier.notifyDataChanged()
                }
                cleanupCounters(subId)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleWriteFailure(subId, identity, e)
                // Сессия принудительно очищается внутри handleWriteFailure при превышении
                // лимита попыток. Это предотвращает перманентную блокировку трекинга.
                // Строгое соблюдение API: на AOSP DataStore.edit не бросает исключений.
                false
            }

        /**
         * Обработка сбоя записи в DataStore с лимитом попыток.
         *
         * **Контракт AOSP:** на AOSP DataStore.edit не бросает исключений при корректном
         * состоянии хранилища.
         *
         * **Отклонение кастомной прошивки:** Сбой возможен только при деградации DataStore на
         * кастомных прошивках. Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** При превышении [MAX_WRITE_ATTEMPTS] сессия принудительно
         * очищается, запись теряется.
         */
        private suspend fun handleWriteFailure(
            subId: Int,
            identity: String,
            exception: Exception,
        ) {
            val attempts = (writeAttempts[subId] ?: 0) + 1
            writeAttempts[subId] = attempts

            if (attempts >= MAX_WRITE_ATTEMPTS) {
                val message =
                    "Call duration write failed for identity $identity " +
                        "(subId=$subId) after $attempts attempts. Session cleared, call data lost."
                AppLogger.e(message, exception)

                // Очищаем сессию, чтобы следующий звонок мог начать новую сессию.
                // Строгое соблюдение API: на AOSP DataStore.edit не бросает исключений.
                // Превышение MAX_WRITE_ATTEMPTS возможно только при деградации DataStore
                // на кастомных прошивках, не соответствующих документации Google.
                // Это проблема кастомной прошивки. Стратегия: теряем данные одного звонка,
                // но предотвращаем перманентную блокировку трекинга.
                try {
                    clearSession(subId)
                } catch (e: Exception) {
                    AppLogger.w("CallTracker: Final attempt to clear session failed for subId $subId", e)
                }

                cleanupCounters(subId)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        message,
                    )
            } else {
                AppLogger.e(
                    "Call duration write failed for identity $identity " +
                        "(subId=$subId, attempt $attempts). Session preserved for retry.",
                    exception,
                )
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Call duration write failed, session preserved",
                    )
            }
        }

        private suspend fun handleAttributionFailure(subId: Int): Boolean {
            val failures = (attributionFailures[subId] ?: 0) + 1
            attributionFailures[subId] = failures
            return if (failures >= MAX_ATTRIBUTION_FAILURES) {
                AppLogger.e(
                    "Call attribution failed $failures times for subId $subId. " +
                        "Force-clearing session to prevent permanent block. " +
                        "Call data for this session is lost.",
                )
                appSettingsRepository.updateAppSettings {
                    it.copy(inaccurateCallAttributionsCount = it.inaccurateCallAttributionsCount + 1)
                }

                // Принудительная очистка: удаляем оба счётчика, чтобы избежать
                // накопления записей для subId, которые больше не обслуживаются.
                // Это проблема кастомной прошивки, если атрибуция не удаётся
                // MAX_ATTRIBUTION_FAILURES раз подряд — на AOSP getIdentity()
                // не бросает исключений при корректном состоянии DataStore.
                attributionFailures.remove(subId)
                writeAttempts.remove(subId)

                true // Сигнализируем endCall, что сессию нужно очистить
            } else {
                AppLogger.e(
                    "Could not attribute call duration for subId $subId " +
                        "(attempt $failures/$MAX_ATTRIBUTION_FAILURES)",
                )
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Attribution failed for subId $subId",
                    )
                writeAttempts.remove(subId)
                false
            }
        }

        private fun cleanupCounters(subId: Int) {
            writeAttempts.remove(subId)
            attributionFailures.remove(subId)
        }

        companion object {
            /**
             * Число попыток чтения из DataStore (включая первую).
             * 3 попытки с экспоненциальной задержкой 500→1000 мс
             * обеспечивают устойчивость к временной занятости хранилища.
             *
             * Строгое соблюдение API: на AOSP DataStore отвечает < 100 мс.
             * Таймаут здесь возможен только при деградации хранилища
             * на кастомной прошивке, не соответствующей документации Google.
             */
            private const val READ_RETRY_ATTEMPTS = 3
            private const val READ_RETRY_DELAY_MS = 500L
            private const val MAX_WRITE_ATTEMPTS = 3
            private const val MAX_ATTRIBUTION_FAILURES = 3
        }
    }

/**
 * Вспомогательный класс для разрешения завершения звонков в неопределенных ситуациях.
 * Вынесен из [CallTracker] для уменьшения количества методов в основном классе.
 */
internal class CallEndResolver(
    @Suppress("unused") private val context: Context,
    private val callSessionRepository: CallSessionRepository,
    private val permissionManager: PermissionManager,
    private val telephonyManager: TelephonyManager,
    private val dataChangeNotifier: DataChangeNotifier,
    private val endCall: suspend (Int, Boolean) -> Unit,
    private val setError: (TrackingStatus.Error) -> Unit,
) {
    /**
     * Разрешение завершения звонка при subId == -1.
     *
     * **Строгое соблюдение API:** на AOSP subId всегда известен при наличии
     * READ_PHONE_STATE. subId == -1 возможен только на кастомных прошивках,
     * не передающих subscription ID в intent. Это проблема кастомной прошивки.
     *
     * **Эвристика в пределах критерия надёжности:** при subId == -1 применяется эвристика
     * определения завершившегося звонка (все в IDLE → закрыть все; ровно один в IDLE → закрыть его;
     * неоднозначность → не закрывать ничего и установить Error).
     *
     * При отзыве разрешения сессии не очищаются (мы не можем достоверно определить,
     * какой звонок завершился), но статус переводится в Error для информирования пользователя.
     */
    suspend fun resolveUnknownSubIdEnd() {
        if (!permissionManager.hasPhoneStatePermission()) {
            AppLogger.w("CallEndResolver: permission revoked during resolveUnknownSubIdEnd")
            setError(
                TrackingStatus.Error(
                    TrackingStatus.ErrorType.PERMISSION_MISSING,
                    "Permission revoked during call end resolution",
                ),
            )
            dataChangeNotifier.notifyDataChanged()
            return
        }
        val activeSubIds =
            try {
                callSessionRepository.getActiveCallSubIds()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to read active call sessions on unknown subId end", e)
                emptyList()
            }

        if (activeSubIds.isEmpty()) {
            AppLogger.d("Call ended with unknown subId and no active sessions found")
        } else if (isDeviceIdle(activeSubIds)) {
            handleGlobalIdle(activeSubIds)
        } else {
            val target: Int? = findTargetSubId(activeSubIds)
            if (target != null) {
                endCall(target, true)
            } else {
                // Неоднозначная ситуация: невозможно определить, какой вызов завершился.
                // Мапы writeAttempts и attributionFailures НЕ очищаются — вызовы ещё активны.
                // Строгое соблюдение API: на AOSP subId всегда известен при наличии
                // READ_PHONE_STATE. Неоднозначность возможна только на кастомных прошивках,
                // не передающих subId в PHONE_STATE_CHANGED intent. Это проблема кастомной прошивки.
                // Эвристика в пределах критерия надёжности: НЕ закрываем ни один вызов,
                // чтобы не потерять данные. Эвристики «закрыть самый старый» или
                // «закрыть default voice» намеренно исключены — они могут привести
                // к потере данных при одновременных вызовах на dual-SIM.
                handleAmbiguousIdle(activeSubIds)
            }
        }
    }

    private fun isDeviceIdle(activeSubIds: List<Int>): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activeSubIds.all { id ->
                // Permission verified at caller (resolveUnknownSubIdEnd check hasPhoneStatePermission)
                @SuppressLint("MissingPermission")
                val state = telephonyManager.createForSubscriptionId(id).callStateForSubscription
                state == TelephonyManager.CALL_STATE_IDLE
            }
        } else {
            // Подавление DEPRECATION необходимо для поддержки устройств с API < 31, где прямой доступ
            // к состоянию конкретной подписки через callStateForSubscription отсутствует.
            @Suppress("DEPRECATION")
            // Permission verified at caller
            @SuppressLint("MissingPermission")
            val state = telephonyManager.callState
            state == TelephonyManager.CALL_STATE_IDLE
        }

    private suspend fun handleGlobalIdle(activeSubIds: List<Int>) {
        AppLogger.d("Call ended with unknown subId and global state is IDLE. Closing all sessions.")
        for (subId in activeSubIds) {
            endCall(subId, false)
        }
        dataChangeNotifier.notifyDataChanged()
    }

    /**
     * Обработка неопределенного завершения звонка.
     *
     * **Контракт AOSP:** на AOSP при корректной работе TelephonyManager subId всегда известен.
     *
     * **Отклонение кастомной прошивки:** Неоднозначность возможна только на кастомных прошивках.
     * Это проблема кастомной прошивки, а не приложения.
     *
     * **Стратегия приложения:** Отклонения не маскируются эвристиками, а обрабатываются как системные сбои.
     */
    private fun handleAmbiguousIdle(activeSubIds: List<Int>) {
        val message = "Cannot attribute unknown IDLE event for multiple active calls (${activeSubIds.size})"
        AppLogger.e(message, null)
        // **Контракт AOSP:** неоднозначность — ошибка, а не повод для угадывания.
        // Эвристики (oldest session, default voice SIM) не применяются.
        setError(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, message))
    }

    @SuppressLint("MissingPermission")
    private fun findTargetSubId(activeSubIds: List<Int>): Int? {
        // Permission verified at caller
        return when {
            activeSubIds.size == 1 -> activeSubIds.first()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                activeSubIds
                    .asSequence()
                    .filter { id ->
                        // Permission verified at caller
                        @SuppressLint("MissingPermission")
                        val state = telephonyManager.createForSubscriptionId(id).callStateForSubscription
                        state == TelephonyManager.CALL_STATE_IDLE
                    }.singleOrNull()
            }
            else -> null
        }
    }
}
