package com.example.minutewatcher.core.data

import android.annotation.SuppressLint
import android.os.Build
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.common.util.firstWithTimeout
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

        private val writeAttempts = ConcurrentHashMap<Int, Int>()
        private val attributionFailures = ConcurrentHashMap<Int, Int>()

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

        override suspend fun recover() {
            callMutex.withLock {
                _status.value = TrackingStatus.Idle
                if (checkPermissions()) {
                    refreshStatus(force = true)
                }
            }
        }

        private suspend fun isCallTrackingEnabled(): Boolean =
            appSettingsRepository.getAppSettings().firstWithTimeout()?.callTrackingEnabled
                ?: throw DataStoreTimeoutException("Settings DataStore read timeout")

        /**
         * Called on device boot to clean up any stale state.
         */
        suspend fun onBoot() {
            callMutex.withLock {
                val activeSessions = callSessionRepository.getActiveCallSubIds()
                callSessionRepository.clearAllCallSessions()
                _status.value = TrackingStatus.Idle
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
                    if (!isCallTrackingEnabled() || !checkPermissions()) return@withLock

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
         * Reads data with retry and timeout.
         *
         * Таймаут по умолчанию — [Constants.Flow.SHORT_TIMEOUT_MS].
         * Для чтения сессий звонков используется [Constants.Flow.CALL_SESSION_READ_TIMEOUT_MS] (2000 мс)
         * — см. документацию константы.
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
            for (attempt in 1..attempts) {
                val result =
                    withTimeoutOrNull(timeoutMs.milliseconds) {
                        reader().first()
                    }
                result?.let { return Result.success(it) }
                if (attempt < attempts) {
                    AppLogger.d("readWithRetry($label): attempt $attempt timed out, retrying in ${currentDelay}ms")
                    delay(currentDelay.milliseconds)
                    currentDelay *= 2
                }
            }
            AppLogger.w("readWithRetry($label): all $attempts attempts timed out")
            return Result.failure(DataStoreTimeoutException("readWithRetry($label) timed out"))
        }

        // Подавление LongMethod и ReturnCount оправдано: метод инкапсулирует атомарную логику захвата
        // начала звонка, включая проверку разрешений, детекцию устаревших сессий и очистку стейта.
        // Разделение метода нарушит линейность восприятия критической секции старта сессии.
        private suspend fun handleCallStartInternal(subId: Int): Boolean {
            val result =
                readWithRetry(
                    label = "callStartData",
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
                AppLogger.e("Call start event dropped due to DataStore timeout for subId $subId", null)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout reading session data for subId $subId",
                    )
                withContext(NonCancellable) {
                    try {
                        clearSession(subId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w("Failed to clear session for subId $subId after start timeout", e)
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

            if (!isCallTrackingEnabled()) {
                AppLogger.d("Call tracking disabled, cleaning up session for subId $subId")
                if (subId != -1) clearSession(subId)
                return
            }

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
         * Это строгое соблюдение контракта: сессия с ненулевым startTime блокирует следующий звонок.
         * Потеря данных одного звонка предпочтительнее полной блокировки трекинга.
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
                // Строгое соблюдение API: на AOSP старт вызова всегда предшествует завершению и фиксируется в OFFHOOK.
                // Отсутствие сессии при IDLE означает, что старт не был захвачен (приложение не работало, либо
                // прошивка не доставила событие). Это не ошибка логики, а диагностический случай; он логируется
                // для наблюдаемости.
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
         * Строгое соблюдение контракта: сессия с ненулевым startTime блокирует
         * следующий звонок на том же subId. При таймауте чтения сессия
         * принудительно очищается, чтобы не допустить перманентной блокировки.
         * На AOSP чтение DataStore завершается успешно; таймаут возможен только
         * при деградации хранилища на кастомных прошивках.
         * Потеря данных одного звонка предпочтительнее перманентной блокировки.
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

            // Строгое соблюдение API: проверка „протухшей“ сессии выполняется по монотонному
            // SystemClock.elapsedRealtime() (savedBootTime → currentBootTime), а не по wall-clock.
            // Это гарантирует корректность при изменении пользователем системного времени и после reboot.
            // Wall-clock (startTime) для оценки длительности/протухания не используется.
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
            } else {
                AppLogger.e(
                    "Call duration write failed for subId $subId (duration: $durationSeconds s), " +
                        "session preserved",
                )
            }
        }

        /**
         * Обрабатывает устаревшую сессию (длительность > MAX_CALL_DURATION_MS).
         * Строгое соблюдение API: сессия не очищается автоматически, статус
         * переводится в Error. Решение о дальнейших действиях остаётся за
         * пользователем/оператором. На AOSP сессии длительностью > 12 часов
         * не возникают; превышение возможно только на кастомных прошивках.
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
                AppLogger.e("Call session data read timeout for subId $subId", null)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.UNKNOWN,
                        "Timeout loading session data for subId $subId",
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
                        current.type == TrackingStatus.ErrorType.UNKNOWN ||
                            current.type == TrackingStatus.ErrorType.PERMISSION_MISSING ||
                            current.type == TrackingStatus.ErrorType.DATABASE_ERROR ||
                            current.type == TrackingStatus.ErrorType.INACCURATE_ATTRIBUTION
                    )

            if (!isIgnoredError) {
                val hasActiveCalls = callSessionRepository.getActiveCallSubIds().isNotEmpty()
                _status.value = if (hasActiveCalls) TrackingStatus.Active else TrackingStatus.Idle
            }
        }

        /**
         * Очистка данных сессии.
         * Реализовано строгое соблюдение API. Отклонения кастомных прошивок не маскируются эвристиками,
         * а обрабатываются как системные сбои с уведомлением пользователя.
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
         * Если атрибуция не удаётся MAX_ATTRIBUTION_FAILURES раз подряд, сессия принудительно очищается.
         * Это строгое соблюдение контракта: на AOSP атрибуция всегда успешна при наличии SIM и READ_PHONE_STATE.
         * Перманентный сбой атрибуции возможен только на кастомных прошивках, не соответствующих документации Google
         * (отсутствие activeSubscriptionInfoList при наличии активного звонка).
         * В этом случае потеря данных одного звонка предпочтительнее перманентной блокировки трекинга.
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

            if (!isCallTrackingEnabled()) {
                AppLogger.d("Call tracking disabled, skipping duration recording for subId $subId")
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

            // Строгое соблюдение API: на AOSP subId вызова однозначно известен при наличии READ_PHONE_STATE,
            // и identity сохраняется при старте вызова (savedIdentity). Fallback на SIM по умолчанию срабатывает,
            // только если identity не был сохранён, что возможно на кастомных прошивках, не соответствующих
            // документации Google. Такая атрибуция считается неточной и учитывается в
            // inaccurateCallAttributionsCount; это проблема кастомной прошивки, а не штатного пути.
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
            }

        private fun handleWriteFailure(
            subId: Int,
            identity: String,
            exception: Exception,
        ): Boolean {
            val attempts = (writeAttempts[subId] ?: 0) + 1
            writeAttempts[subId] = attempts

            /**
             * Handle DataStore write failure with a retry limit.
             *
             * Strategy:
             * 1. **Preserve Session**: Initially, we don't clear the session in DataStore, giving a chance
             *    for a retry on the next IDLE event (if any).
             * 2. **Avoid Deadlock**: However, an uncleared session blocks the tracking of the NEXT call
             *    on the same subId (it will be seen as a duplicate OFFHOOK).
             * 3. **Terminal Cleanup**: After [MAX_WRITE_ATTEMPTS], we prioritize future tracking over
             *    the current lost record. We clear the session and accept the data loss.
             *
             * This is a strict protection against permanent tracking loss for a specific SIM,
             * preventing a single failed write from "hanging" the tracker indefinitely.
             */
            return if (attempts >= MAX_WRITE_ATTEMPTS) {
                val message =
                    "Call duration write failed for identity $identity " +
                        "(subId=$subId) after $attempts attempts. Session preserved."
                AppLogger.e(message, exception)
                cleanupCounters(subId)
                _status.value =
                    TrackingStatus.Error(
                        TrackingStatus.ErrorType.DATABASE_ERROR,
                        message,
                    )
                false // Session is NOT closed
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
                false
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
     * Реализовано строгое соблюдение API. Отклонения кастомных прошивок не маскируются эвристиками,
     * а обрабатываются как системные сбои с уведомлением пользователя.
     */
    private fun handleAmbiguousIdle(activeSubIds: List<Int>) {
        val message = "Cannot attribute unknown IDLE event for multiple active calls (${activeSubIds.size})"
        AppLogger.e(message, null)
        // Строгое соблюдение API: эвристики (oldest session, default voice SIM)
        // не применяются. Неоднозначность — ошибка, а не повод для угадывания.
        // На AOSP при корректной работе TelephonyManager subId всегда известен;
        // неоднозначность возможна только на кастомных прошивках, не
        // соответствующих документации Google.
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
