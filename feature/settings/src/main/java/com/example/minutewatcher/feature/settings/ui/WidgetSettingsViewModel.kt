package com.example.minutewatcher.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SimSettingsState
import com.example.minutewatcher.core.model.WidgetSettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class WidgetSettingsViewModel
    @Inject
    constructor(
        private val loader: WidgetSettingsLoader,
        private val saver: WidgetSettingsSaver,
        usageStateRepository: UsageStateRepository,
    ) : ViewModel() {
        private val _settingsState = MutableStateFlow<WidgetSettingsState?>(null)
        val settingsState: StateFlow<WidgetSettingsState?> = _settingsState.asStateFlow()

        private val _loadError = MutableStateFlow(false)
        val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

        private val saveMutex = Mutex()
        private val _isSaving = MutableStateFlow(false)
        val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

        /**
         * Однократное событие результата сохранения.
         * UI должен прочитать значение и затем вызвать consumeSaveResult() для сброса.
         */
        private val _saveResult = MutableStateFlow<SaveResult?>(null)
        val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

        /**
         * Мьютекс гарантирует, что результат отменённой загрузки не будет записан в
         * [_settingsState], даже если [Job.cancel] вызван между launch и withLock.
         */
        private val loadMutex = Mutex()

        private var loadJob: Job? = null

        val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()

        init {
            loadSettings()
        }

        fun retryLoad() {
            loadSettings()
        }

        /**
         * Загружает настройки виджета, используя паттерн отмены предыдущей загрузки.
         *
         * [Job.cancel] инициирует кооперативную отмену предыдущей корутины.
         * [loadMutex] гарантирует, что только одна (последняя актуальная) корутина
         * получит доступ к обновлению [_settingsState] после завершения работы [loader].
         * Вызов [ensureActive] внутри блокировки мьютекса защищает от записи данных
         * в состояние, если корутина была отменена во время загрузки.
         */
        private fun loadSettings() {
            _loadError.value = false
            // loadMutex обеспечивает сериализацию загрузок. При отмене предыдущей
            // корутины (loadJob?.cancel()) Mutex освобождается автоматически
            // (Kotlin Mutex.release при CancellationException). Новая корутина
            // захватывает Mutex и выполняет загрузку. Это гарантирует, что
            // только одна загрузка выполняется одновременно.
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    loadMutex.withLock {
                        // ensureActive() проверяет, не была ли корутина отменена
                        // между захватом мьютекса и началом загрузки. Если отмена
                        // произошла, CancellationException пробрасывается, мьютекс
                        // освобождается автоматически, и состояние НЕ обновляется.
                        // Это корректное поведение: отменённая загрузка не должна
                        // перезаписывать состояние данными.
                        //
                        // **Строгое соблюдение API kotlinx.coroutines:** Mutex.release
                        // вызывается автоматически при CancellationException внутри
                        // withLock. Это гарантирует отсутствие deadlock.
                        try {
                            val loaded =
                                withTimeoutOrNull(timeout = LOAD_TIMEOUT) {
                                    loader.load()
                                }
                            // ensureActive() вызывается сразу после suspend-вызова для гарантии
                            // кооперативной отмены. Если корутина была отменена во время
                            // withTimeoutOrNull, CancellationException будет проброшен до
                            // проверки результата. Это строгое соблюдение API kotlinx.coroutines:
                            // CancellationException должен пробрасываться для корректной отмены scope.
                            ensureActive()
                            if (loaded != null) {
                                _settingsState.value = loaded
                            } else {
                                AppLogger.e("Failed to load widget settings: timeout")
                                _loadError.value = true
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("Failed to load widget settings", e)
                            _loadError.value = true
                        }
                    }
                }
        }

        fun updateSettings(transform: (WidgetSettingsState) -> WidgetSettingsState) {
            _settingsState.update { current ->
                current?.let(transform) ?: current
            }
        }

        fun consumeSaveResult() {
            _saveResult.value = null
        }

        fun updateSimSettings(
            slot: Int,
            update: (SimSettingsState) -> SimSettingsState,
        ) {
            // Защитная валидация: UI должен передавать только валидные слоты,
            // но проверяем на случай изменения UI или программной ошибки.
            if (slot !in Constants.SimConstants.SUPPORTED_SLOT_INDICES) {
                AppLogger.w("updateSimSettings called with invalid slot $slot, ignoring")
                return
            }
            _settingsState.update { currentState ->
                if (currentState == null) return@update null

                val currentSims = currentState.simStates.toMutableMap()
                val simState = currentSims[slot] ?: SimSettingsState()
                currentSims[slot] = update(simState)
                currentState.copy(simStates = currentSims)
            }
        }

        /**
         * Сохраняет текущие настройки и уведомляет систему об изменениях.
         *
         * Строгое соблюдение API: если settingsLoadFailed = true, сохранение
         * блокируется. Это предотвращает перезапись реальных настроек
         * дефолтными значениями из fallback-состояния.
         * На AOSP WidgetSettingsLoader.load() завершается за миллисекунды.
         * Таймаут указывает на деградацию хранилища на кастомной прошивке.
         * Повторная попытка через retryLoad() — единственная стратегия восстановления.
         *
         * Точки возврата:
         * 1. SaveResult.AlreadyInProgress — сохранение уже выполняется (защита от гонки).
         * 2. SaveResult.Failed — загрузка настроек завершилась ошибкой ранее.
         * 3. SaveResult.Failed — состояние ещё не загружено (null).
         * 4. SaveResult.PartialSuccess(skippedSimSettings=true) — simListLoadFailed:
         *    сохраняются только общие настройки (skipSimSettings=true).
         *    SIM-специфичные настройки пропускаются для предотвращения потери данных.
         * 5. SaveResult.Success — нормальное сохранение всех настроек.
         *
         * @Suppress("ReturnCount") оправдан: каждая точка возврата соответствует
         * отдельному защитному кейсу. Объединение снизило бы читаемость.
         */
        @Suppress("ReturnCount")
        suspend fun saveAndNotify(): SaveResult {
            if (!saveMutex.tryLock()) {
                // Не записываем в _saveResult, чтобы не перезаписать предыдущий результат.
                // UI получит AlreadyInProgress напрямую из возвращаемого значения.
                return SaveResult.AlreadyInProgress
            }
            return try {
                _isSaving.value = true
                val result = performSaveInternal()
                _saveResult.value = result
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                val result = SaveResult.Failed
                _saveResult.value = result
                result
            } finally {
                _isSaving.value = false
                saveMutex.unlock()
            }
        }

        @Suppress("ReturnCount")
        private suspend fun performSaveInternal(): SaveResult {
            if (_loadError.value) {
                AppLogger.w("saveAndNotify skipped: settings load failed previously")
                return SaveResult.Failed
            }

            val currentState = _settingsState.value
            if (currentState == null) {
                AppLogger.w("saveAndNotify called before settings were loaded")
                return SaveResult.Failed
            }

            if (currentState.settingsLoadFailed) {
                AppLogger.w("saveAndNotify: skipping save because settings were not loaded successfully")
                return SaveResult.Failed
            }

            return if (currentState.simListLoadFailed) {
                savePartialSettings(currentState)
            } else {
                saveFullSettings(currentState)
            }
        }

        private suspend fun savePartialSettings(state: WidgetSettingsState): SaveResult {
            AppLogger.w(
                "saveAndNotify: SIM list was not loaded successfully. " +
                    "Saving only general settings via skipSimSettings = true.",
            )
            val validatedState = validateSettings(state)
            val success = saver.save(validatedState, skipSimSettings = true)
            return if (success) {
                SaveResult.PartialSuccess(skippedSimSettings = true)
            } else {
                SaveResult.Failed
            }
        }

        private suspend fun saveFullSettings(state: WidgetSettingsState): SaveResult {
            val validatedState = validateSettings(state)
            val success = saver.save(validatedState)
            return if (success) SaveResult.Success else SaveResult.Failed
        }

        /**
         * Валидирует настройки перед сохранением.
         * Строгое соблюдение API: значения coerceIn гарантируют,
         * что виджет получит корректные параметры даже если UI
         * передал некорректные данные (например, из-за бага в слайдере).
         */
        private fun validateSettings(state: WidgetSettingsState): WidgetSettingsState =
            state.copy(
                layoutMode =
                    state.layoutMode.coerceIn(
                        Constants.Widget.MIN_LAYOUT_MODE,
                        Constants.Widget.MAX_LAYOUT_MODE,
                    ),
                simArrangement =
                    state.simArrangement.coerceIn(
                        Constants.Widget.MIN_SIM_ARRANGEMENT,
                        Constants.Widget.MAX_SIM_ARRANGEMENT,
                    ),
                transparency =
                    state.transparency.coerceIn(
                        Constants.Widget.MIN_TRANSPARENCY,
                        Constants.Widget.MAX_TRANSPARENCY,
                    ),
                fontSize =
                    state.fontSize.coerceIn(
                        Constants.Widget.MIN_FONT_SIZE,
                        Constants.Widget.MAX_FONT_SIZE,
                    ),
                // Сбрасываем флаг превышения лимита при сохранении настроек пользователем.
                // Это позволяет вручную "вылечить" виджет, если пользователь изменил состав данных.
                widgetLimitExceeded = false,
            )

        companion object {
            /**
             * Таймаут загрузки настроек виджета.
             *
             * **Строгое соблюдение API:** на AOSP WidgetSettingsLoader.load() завершается
             * за миллисекунды. 10 секунд — защитный запас.
             *
             * **Отклонение кастомной прошивки:** если DataStore деградирует и load()
             * занимает больше 10 секунд — это проблема кастомной прошивки.
             */
            private val LOAD_TIMEOUT: Duration = 10.seconds
        }
    }
