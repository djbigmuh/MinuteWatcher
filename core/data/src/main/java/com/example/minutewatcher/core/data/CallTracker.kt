package com.example.minutewatcher.core.data

import android.annotation.SuppressLint
import android.os.Build
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

private class DataStoreTimeoutException(
    message: String,
) : Exception(message)

// Подавление LargeClass оправдано: CallTracker является центральным узлом обработки событий звонков,
// объединяющим логику старта, завершения, атрибуции и восстановления состояния после сбоев.
// Дальнейшее дробление класса усложнит координацию критической секции трекинга.
@Suppress("LargeClass")
@Singleton
class CallTracker
    @Inject
    constructor(
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

        /**
         * Счётчики неудачных попыток записи длительности звонка.
         * Ключ — subId, значение — число последовательных неудач.
         *
         * Записи создаются в [handleWriteFailure] и удаляются в [cleanupCounters]
         * при успешном завершении звонка. Дополнительно очищаются в [onBoot]
         * для предотвращения накопления устаревших записей.
         *
         * **Контракт AOSP:** на AOSP DataStore.edit не бросает исключений при корректном
         * состоянии хранилища.
         *
         * **Отклонение кастомной прошивки:** Сбой записи возможен только при деградации DataStore
         * на кастомных прошивках, не соответствующих документации Google.
         * Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** используется механизм повторных попыток с лимитом [MAX_WRITE_ATTEMPTS],
         * после которого сессия принудительно очищается для предотвращения блокировки.
         */
        private val writeAttempts = ConcurrentHashMap<Int, Int>()

        private val attributionFailures = ConcurrentHashMap<Int, Int>()

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
                _status.value = TrackingStatus.Idle
                if (checkPermissions()) {
                    refreshStatus(force = true)
                }
            }
        }

        /**
         * Called on device boot to clean up any stale state.
         */
        suspend fun onBoot() {
            callMutex.withLock {
                val activeSessions = callSessionRepository.getActiveCallSubIds()
                callSessionRepository.clearAllCallSessions()
                _status.value = TrackingStatus.Idle
                // Очистка мап попыток: после перезагрузки все предыдущие сессии неактуальны.
                // Строгое соблюдение API: на AOSP onBoot вызывается один раз при загрузке;
                // мапы не должны содержать записей от предыдущего сеанса работы устройства.
                writeAttempts.clear()
                attributionFailures.clear()
                AppLogger.d("Cleared ${activeSessions.size} stale call sessions on boot")
            }
        }

        /**
         * Called when ACTION_PHONE_STATE_CHANGED receives OFFHOOK.
         */
        suspend fun onCallStarted(subId: Int) {
            callMutex.withLock {
                var justStarted = false
                try {
                    if (!isCallTrackingEnabled()) {
                        AppLogger.d("Call tracking is disabled in settings, ignoring onCallStarted")
                        return@withLock
                    }
                    if (!checkPermissions()) return@withLock

                    val effectiveSubId = resolveEffectiveSubId(subId)
                    if (effectiveSubId == -1) return@withLock

                    val isNewSessionStarted = handleCallStartInternal(effectiveSubId)
                    if (isNewSessionStarted) {
                        justStarted = true
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
                    withContext(NonCancellable) {
                        try {
                            if ((_status.value !is TrackingStatus.Error) && !justStarted) {
                                refreshStatus()
                            }
                        } catch (e: CancellationException) {
                            // Подавление ThrowingExceptionFromFinally оставлено намеренно: в Kotlin Coroutines
                            // проброс CancellationException из finally блока является стандартным способом
                            // обеспечения корректной остановки иерархии корутин при отмене.
                            @Suppress("ThrowingExceptionFromFinally")
                            throw e
                        } catch (e: Exception) {
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
         * **Контракт AOSP:** на AOSP DataStore.data всегда эмитит хотя бы одно значение.
         *
         * **Отклонение кастомной прошивки:** Сбой (таймаут или исключение) возможен только на кастомных прошивках.
         * Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** используется механизм повторных попыток [attempts] с таймаутом [timeoutMs].
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
                        clearSession(subId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w("Failed to clear session for subId $subId after start timeout", e)
                        _status.value =
                            TrackingStatus.Error(
                                TrackingStatus.ErrorType.DATABASE_ERROR,
                                "Session cleanup failed for subId $subId",
                            )
                        // **Контракт AOSP:** на AOSP DataStore.edit не бросает исключений при
                        // корректном состоянии хранилища.
                        //
                        // **Отклонение кастомной прошивки:** Двойной сбой (read + clear) возможен только при
                        // деградации DataStore на кастомных прошивках, не соответствующих документации Google.
                        // Это проблема кастомной прошивки.
                        //
                        // **Стратегия приложения:** устанавливаем Error-статус, чтобы UI уведомил пользователя.
                        // Повторная попытка возможна при следующем вызове onCallStarted.
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
                var errorSetInThisCall = false
                try {
                    executeCallEnded(subId)
                    if (_status.value is TrackingStatus.Error) {
                        // Фиксируем, что была установлена ошибка (например, провал атрибуции),
                        // чтобы блок finally не сбросил её в Idle/Active через refreshStatus().
                        errorSetInThisCall = true
                    }
                } catch (e: SecurityException) {
                    handleSecurityException(e)
                    errorSetInThisCall = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.e("Error in onCallEnded", e)
                    _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN, e.message)
                    errorSetInThisCall = true

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
                    withContext(NonCancellable) {
                        try {
                            if (!errorSetInThisCall) {
                                refreshStatus()
                            }
                        } catch (e: CancellationException) {
                            // Проброс CancellationException для корректного завершения CoroutineScope.
                            @Suppress("ThrowingExceptionFromFinally")
                            throw e
                        } catch (e: Exception) {
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
         * Обработка таймаута чтения данных сессии при завершении звонка.
         *
         * **Контракт AOSP:** на AOSP DataStore отвечает < 100 мс.
         *
         * **Отклонение кастомной прошивки:** Таймаут срабатывает только при деградации
         * хранилища на кастомных прошивках, не соответствующих документации Google.
         * Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** сессия принудительно очищается, длительность звонка теряется.
         * Это предотвращает блокировку трекинга на том же subId.
         */
        private suspend fun handleEndCallTimeout(subId: Int) {
            val message = "Session read timed out for subId $subId"
            AppLogger.e(message, null)
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR, message)

            withContext(NonCancellable) {
                try {
                    clearSession(subId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Failed to clear session for subId $subId after timeout", e)
                }
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
            _status.value = TrackingStatus.Error(TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION, message)
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
        private suspend fun refreshStatus(force: Boolean = false) {
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
         * Очистка данных сессии.
         *
         * **Контракт AOSP:** на AOSP DataStore.edit не бросает исключений при корректном
         * состоянии хранилища.
         *
         * **Отклонение кастомной прошивки:** Сбой возможен только при деградации DataStore на
         * кастомных прошивках. Это проблема кастомной прошивки, а не приложения.
         *
         * **Стратегия приложения:** Отклонения не маскируются эвристиками, а обрабатываются как
         * системные сбои с уведомлением.
         */
        private suspend fun clearSession(subId: Int) {
            withContext(NonCancellable) {
                callSessionRepository.setCallStartTime(subId, 0L)
                callSessionRepository.setBootTime(subId, 0L)
                callSessionRepository.setUptime(subId, 0L)
                callSessionRepository.setCallIdentity(subId, null)
                attributionFailures.remove(subId)
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
         */

        @Suppress("ReturnCount")
        // Подавление ReturnCount оправдано: метод содержит 3 точки выхода, соответствующие различным сценариям
        // обработки длительности (нулевая длительность, трекинг отключен, успешная/неудачная атрибуция).
        // Линейная структура с ранними выходами обеспечивает лучшую читаемость критической секции.
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
                attributionFailures.remove(subId)
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
            private const val READ_RETRY_ATTEMPTS = 2
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
     * Строгое соблюдение API: на AOSP subId всегда известен при наличии
     * READ_PHONE_STATE. subId == -1 возможен только на кастомных прошивках,
     * не передающих subscription ID в intent.
     *
     * Стратегия (без хрупких эвристик):
     * 1. Если все активные сессии в IDLE — закрыть все (однозначно).
     * 2. Если ровно одна сессия в IDLE — закрыть её (однозначно).
     * 3. Если неоднозначно — НЕ закрывать ничего, установить Error.
     *    Эвристики «закрыть самую старую» или «закрыть default voice»
     *    намеренно исключены: они могут привести к потере данных
     *    при одновременных звонках на dual-SIM.
     *
     * Отклонение кастомной прошивки: если прошивка не передаёт subId
     * и при этом не обеспечивает корректное состояние IDLE — это проблема
     * кастомной прошивки. Приложение устанавливает Error для информирования
     * пользователя, но не пытается угадать.
     */
    suspend fun resolveUnknownSubIdEnd() {
        if (!permissionManager.hasPhoneStatePermission()) {
            AppLogger.w("CallEndResolver: permission revoked during resolveUnknownSubIdEnd")
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
                handleAmbiguousIdle(activeSubIds)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceIdle(activeSubIds: List<Int>): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activeSubIds.all { id ->
                if (!permissionManager.hasPhoneStatePermission()) return@all true
                telephonyManager.createForSubscriptionId(id).callStateForSubscription ==
                    TelephonyManager.CALL_STATE_IDLE
            }
        } else {
            // Подавление DEPRECATION необходимо для поддержки устройств с API < 31, где прямой доступ
            // к состоянию конкретной подписки через callStateForSubscription отсутствует.
            @Suppress("DEPRECATION")
            telephonyManager.callState == TelephonyManager.CALL_STATE_IDLE
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
        if (!permissionManager.hasPhoneStatePermission()) return null

        return when {
            activeSubIds.size == 1 -> activeSubIds.first()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                activeSubIds
                    .asSequence()
                    .filter { id ->
                        if (!permissionManager.hasPhoneStatePermission()) return@filter false
                        telephonyManager.createForSubscriptionId(id).callStateForSubscription ==
                            TelephonyManager.CALL_STATE_IDLE
                    }.singleOrNull()
            }
            else -> null
        }
    }
}
