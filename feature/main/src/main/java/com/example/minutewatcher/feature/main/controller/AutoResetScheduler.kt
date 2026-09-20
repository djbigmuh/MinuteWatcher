package com.example.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.domain.AutoResetUseCase
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Планировщик автоматического сброса лимитов.
 *
 * **Поведение при гибели процесса:**
 * - [KEY_LAST_AUTO_RESET_CHECK] персистентен в SavedStateHandle.
 * - При гибели процесса и восстановлении ViewModel checkOnStart() выполнится повторно.
 * - Если с момента последней проверки прошло меньше интервала — сброс не выполняется.
 * - Если прошло больше — сброс выполняется.
 *
 * **Строгое соблюдение API:** SavedStateHandle гарантированно восстанавливается
 * Android Framework при воссоздании ViewModel. Атомарность [isCheckStarted]
 * предотвращает конкуренцию при нескольких вызовах в рамках одного экземпляра ViewModel.
 *
 * **Эвристика в пределах критерия надёжности:** при гибели процесса во время
 * проверки сброса, повторная проверка при восстановлении может выполнить
 * сброс дважды (если первый сброс завершился, но флаг не был сохранён).
 * Это допустимо: повторный сброс идемпотентен (сброс уже сброшенных данных
 * не изменяет данные).
 */
class AutoResetScheduler
@Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val autoResetUseCase: AutoResetUseCase,
        private val timeProvider: TimeProvider,
    ) {
        /**
         * Флаг однократного запуска проверки авто-сброса.
         *
         * **Намеренно не сбрасывается при успехе:** проверка авто-сброса
         * должна выполняться ровно один раз за время жизни ViewModel. Повторный вызов
         * `checkOnStart()` после завершения немедленно возвращает управление.
         *
         * Флаг сбрасывается при [CancellationException] или любой неожиданной ошибке (Exception),
         * чтобы позволить повторную попытку при следующей инициализации, так как в этих случаях
         * проверка не была доведена до конца (записи в SavedStateHandle не обновлены).
         * При частичном отказе (`performAutoReset` вернул `false`) флаг НЕ сбрасывается, так как
         * логика ретраев внутри `executeAutoResetWithRetry` уже учла этот сбой.
         */
        private val isCheckStarted = AtomicBoolean(false)

        fun setup() {
            if (!savedStateHandle.contains(KEY_AUTO_RESET_FAIL_COUNT)) {
                savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = 0
            }
        }

        /**
         * Проверяет необходимость авто-сброса при старте.
         *
         * **Строгое соблюдение API:** используется монотонное время
         * (elapsedRealtime), а не wall-clock (System.currentTimeMillis).
         * Это гарантирует, что перевод системных часов пользователем
         * не приведёт к ложным срабатываниям или пропуску проверок.
         *
         * **Поведение при перезагрузке:** `elapsedRealtime()` монотонен только в пределах
         * одной загрузки устройства и сбрасывается в ноль при перезагрузке.
         * Если сохранённое значение `lastResetCheck` больше текущего `now` (отрицательная разность),
         * это означает, что устройство было перезагружено с момента последней проверки.
         * В этом случае проверка выполняется немедленно для обеспечения целостности данных.
         */
        suspend fun checkOnStart() {
            if (!isCheckStarted.compareAndSet(false, true)) return

            try {
                performCheckInternal()
            } catch (e: CancellationException) {
                isCheckStarted.set(false)
                throw e
            } catch (e: Exception) {
                AppLogger.e("Auto-reset failed during initialization", e)
                val currentFailCount = savedStateHandle.get<Int>(KEY_AUTO_RESET_FAIL_COUNT) ?: 0
                savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = currentFailCount + 1
                isCheckStarted.set(false)
            }
        }

        private suspend fun performCheckInternal() {
            val lastResetCheck = savedStateHandle.get<Long>(KEY_LAST_AUTO_RESET_CHECK)
            val failCount = savedStateHandle.get<Int>(KEY_AUTO_RESET_FAIL_COUNT) ?: 0
            val now = timeProvider.elapsedRealtime()

            val elapsed = if (lastResetCheck == null) 0L else (now - lastResetCheck)
            val isInitialOrReboot = (lastResetCheck == null) || (elapsed < 0)

            val effectiveFailCount =
                if (isInitialOrReboot || (elapsed > FAIL_COUNT_RESET_INTERVAL_MS)) 0 else failCount

            if (isInitialOrReboot || (elapsed > AUTO_RESET_CHECK_INTERVAL_MS)) {
                executeAutoResetWithRetry(now, effectiveFailCount)
            }
        }

        private suspend fun executeAutoResetWithRetry(
            now: Long,
            failCount: Int,
        ) {
            if (failCount <= MAX_AUTO_RESET_RETRIES) {
                val allReset = autoResetUseCase.performAutoReset(shouldNotify = false)
                if (!allReset) {
                    AppLogger.w("AutoResetScheduler: Auto-reset partially failed")
                }
                savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = now
                savedStateHandle[KEY_AUTO_RESET_FAIL_COUNT] = if (allReset) 0 else (failCount + 1)
            } else {
                AppLogger.e("Auto-reset skipped after $failCount consecutive failures")
                savedStateHandle[KEY_LAST_AUTO_RESET_CHECK] = now
            }
        }

        companion object {
            private const val KEY_LAST_AUTO_RESET_CHECK = "last_auto_reset_check"
            private const val KEY_AUTO_RESET_FAIL_COUNT = "auto_reset_fail_count"
            private const val AUTO_RESET_CHECK_INTERVAL_MS = 3_600_000L // 1 hour
            private const val MAX_AUTO_RESET_RETRIES = 3
            private const val FAIL_COUNT_RESET_INTERVAL_MS = 24 * 3_600_000L // 24 hours
        }
    }
