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
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    loadMutex.withLock {
                        try {
                            val loaded =
                                withTimeoutOrNull(LOAD_TIMEOUT) {
                                    loader.load()
                                }
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
         * ВАЖНО: Сохранение при [WidgetSettingsState.settingsLoadFailed] = true запрещено,
         * так как в этом случае состояние содержит дефолтные значения (fallback),
         * а не реальные настройки пользователя из DataStore. Сохранение перезаписало бы
         * данные пользователя некорректными значениями.
         *
         * На AOSP settingsLoadFailed всегда false при корректной работе DataStore.
         * Если кастомная прошивка не обеспечивает своевременный ответ DataStore —
         * это проблема кастомной прошивки.
         */
        suspend fun saveAndNotify(): SaveResult {
            if (!saveMutex.tryLock()) {
                // Сохранение уже выполняется — повторный вызов отклоняется, чтобы избежать гонки.
                return SaveResult.AlreadyInProgress
            }
            return try {
                _isSaving.value = true
                val currentState = _settingsState.value
                if (currentState == null) {
                    AppLogger.w("saveAndNotify called before settings were loaded")
                    SaveResult.Failed
                } else if (currentState.settingsLoadFailed) {
                    AppLogger.w(
                        "saveAndNotify: skipping save because settings were not loaded successfully",
                    )
                    SaveResult.Failed
                } else if (currentState.simListLoadFailed) {
                    // Если список SIM не загрузился (таймаут DataStore на кастомной прошивке),
                    // сохраняем общие настройки, но логируем предупреждение.
                    // На AOSP observeActiveSims() завершается за миллисекунды;
                    // таймаут — проблема кастомной прошивки.
                    AppLogger.w(
                        "saveAndNotify: SIM list was not loaded successfully. " +
                            "General settings will be saved, but SIM-specific settings may be incomplete.",
                    )
                    // Сохраняем общие настройки, но логируем предупреждение.
                    // SIM-специфичные настройки могут быть неполными.
                    val validatedState = validateSettings(currentState)
                    val success = saver.save(validatedState)
                    if (success) SaveResult.Success else SaveResult.Failed
                } else {
                    val validatedState = validateSettings(currentState)
                    val success = saver.save(validatedState)
                    if (success) SaveResult.Success else SaveResult.Failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                SaveResult.Failed
            } finally {
                _isSaving.value = false
                saveMutex.unlock()
            }
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
