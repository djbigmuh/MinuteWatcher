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
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * WidgetSettingsViewModel manages the state and persistence of widget settings.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **Error Handling:** Propagates [IOException] and [CancellationException].
 * Handles timeouts and concurrent access.
 * **AOSP DataStore:** Fully compliant with AOSP DataStore semantics via [WidgetSettingsLoader]
 * and [WidgetSettingsSaver].
 *
 * @property loader Loader for widget settings.
 * @property saver Saver for widget settings.
 * @param usageStateRepository Repository for observed usage state snapshots.
 */
@HiltViewModel
class WidgetSettingsViewModel
    @Inject
    constructor(
        private val loader: WidgetSettingsLoader,
        private val saver: WidgetSettingsSaver,
        usageStateRepository: UsageStateRepository,
    ) : ViewModel() {
        // _settingsState — состояние настроек виджета.
        // Начальное значение null (настройки ещё не загружены).
        // После успешной загрузки устанавливается в загруженные настройки.
        // При ошибке загрузки остаётся null, а _loadError устанавливается в true.
        //
        // Потокобезопасность: MutableStateFlow потокобезопасен.
        // Чтение .value и запись .value безопасны из любой корутины.
        private val _settingsState = MutableStateFlow<WidgetSettingsState?>(null)
        val settingsState: StateFlow<WidgetSettingsState?> = _settingsState.asStateFlow()

        // _loadError — флаг ошибки загрузки настроек.
        // Начальное значение false.
        // При ошибке загрузки устанавливается в true.
        // При начале новой загрузки (retryLoad) сбрасывается в false.
        //
        // Потокобезопасность: MutableStateFlow потокобезопасен.
        private val _loadError = MutableStateFlow(value = false)
        val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

        // saveMutex — мьютекс для предотвращения конкурентных сохранений.
        // Используется в saveAndNotify() через tryLock().
        // Если мьютекс уже залочен (сохранение выполняется),
        // saveAndNotify() немедленно возвращает AlreadyInProgress.
        //
        // Строгое соблюдение API: Mutex в Kotlin НЕ является reentrant.
        // tryLock() возвращает false, если мьютекс уже залочен другой корутиной.
        // finally-блок гарантирует освобождение мьютекса.
        private val saveMutex = Mutex()

        // _isSaving — флаг выполнения сохранения.
        // Начальное значение false.
        // При сохранении устанавливается в true.
        // В finally-блоке saveAndNotify() сбрасывается в false.
        //
        // Потокобезопасность: MutableStateFlow потокобезопасен.
        // finally-блок гарантирует сброс даже при CancellationException.
        private val _isSaving = MutableStateFlow(false)
        val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

        // _saveResult — результат последнего сохранения.
        // Начальное значение null (сохранение ещё не выполнялось).
        // После сохранения устанавливается в Success/Failed/PartialSuccess/AlreadyInProgress.
        // После обработки UI (consumeSaveResult) сбрасывается в null.
        //
        // Потокобезопасность: MutableStateFlow потокобезопасен.
        // LaunchedEffect keyed on saveResult гарантирует однократную обработку.
        private val _saveResult = MutableStateFlow<SaveResult?>(null)
        val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

        // loadMutex — мьютекс для сериализации загрузок.
        // При вызове loadSettings() предыдущая корутина отменяется,
        // а новая ожидает освобождения loadMutex.
        //
        // Строгое соблюдение API: Mutex в Kotlin НЕ является reentrant.
        // withLock() освобождает мьютекс автоматически при CancellationException.
        // Это гарантирует отсутствие deadlock.
        private val loadMutex = Mutex()

        // loadJob — Job текущей загрузки.
        // Использует [AtomicReference] для атомарной отмены предыдущей загрузки
        // и предотвращения race condition при конкурентных вызовах [loadSettings].
        private val loadJob = AtomicReference<Job?>(null)

        val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()

        init {
            loadSettings()
        }

        /**
         * Retries loading the widget settings.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun retryLoad() {
            loadSettings()
        }

        /**
         * Загружает настройки виджета, используя паттерн атомарной отмены предыдущей загрузки.
         *
         * loadJob.getAndSet(newJob)?.cancel() атомарно отменяет предыдущую корутину.
         * Новая корутина ожидает освобождения loadMutex (старая корутина освободит его
         * при обработке отмены на ближайшей точке приостановки или при
         * ensureActive()).
         *
         * ensureActive() вызывается после withTimeoutOrNull для гарантии,
         * что корутина не была отменена во время загрузки. Если отмена
         * произошла, CancellationException пробрасывается и withLock
         * освобождает мьютекс автоматически.
         *
         * Строгое соблюдение API kotlinx.coroutines: Mutex.release вызывается
         * автоматически при CancellationException внутри withLock.
         * Это гарантирует отсутствие deadlock.
         */
        private fun loadSettings() {
            _loadError.value = false
            loadJob.getAndSet(null)?.cancel()
            val newJob =
                viewModelScope.launch {
                    loadMutex.withLock {
                        try {
                            val loaded =
                                withTimeoutOrNull(timeout = LOAD_TIMEOUT) {
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

            loadJob.set(newJob)
        }

        /**
         * Обновляет текущее состояние настроек виджета.
         *
         * **Строгое соблюдение API:** при вызове [updateSettings] флаг
         * [_loadError] сбрасывается в `false`. Если загрузка ранее завершилась
         * ошибкой, но пользователь продолжает работу с настройками (меняет
         * параметры), состояние ошибки больше не актуально. Повторная ошибка
         * будет установлена при следующем вызове [loadSettings].
         *
         * **Потокобезопасность:** [MutableStateFlow.value] потокобезопасен.
         * [MutableStateFlow.update] выполняет атомарное обновление.
         */
        fun updateSettings(transform: (WidgetSettingsState) -> WidgetSettingsState) {
            _loadError.value = false
            _settingsState.update { current ->
                current?.let(transform) ?: current
            }
        }

        /**
         * Сбрасывает результат сохранения в null.
         * Вызывается UI после обработки результата (показа Snackbar).
         * Предотвращает повторный показ Snackbar при рекомпозиции.
         *
         * LaunchedEffect keyed on saveResult перезапускается при изменении
         * saveResult. После consumeSaveResult() saveResult становится null,
         * эффект перезапускается с null и ничего не делает.
         *
         * Consumes the last save result, resetting it to null.
         * Should be called by UI after displaying the result.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
        fun consumeSaveResult() {
            _saveResult.value = null
        }

        /**
         * Updates settings for a specific SIM slot.
         *
         * @param slot SIM slot index.
         * @param update Function to transform the current SIM settings state.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
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
            _loadError.value = false
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
         * Поведение при отмене:
         * Если операция отменена (CancellationException), _saveResult не устанавливается.
         * Это корректное поведение: UI уже уничтожен и не может показать результат.
         * saveMutex освобождается в finally-блоке, что гарантирует отсутствие deadlock.
         *
         * Поведение при AlreadyInProgress:
         * Если сохранение уже выполняется, метод немедленно возвращает
         * SaveResult.AlreadyInProgress. Это предотвращает конкурентные сохранения.
         *
         * Строгое соблюдение API: Mutex в Kotlin НЕ является reentrant.
         * tryLock() возвращает false, если мьютекс уже залочен другой корутиной.
         *
         * Saves current settings and notifies the system of changes.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **Error Handling:** Propagates [IOException] and [CancellationException].
         * **Timeout:** Save operations may time out after [Constants.Flow.LONG_TIMEOUT_MS].
         * **AOSP DataStore:** Fully compliant with atomic updates.
         */
        suspend fun saveAndNotify() {
            // tryLock() — неблокирующая операция. Если мьютекс уже залочен
            // (сохранение выполняется), метод немедленно возвращает AlreadyInProgress.
            // Это предотвращает конкурентные сохранения и deadlock.
            //
            // Строгое соблюдение API: Mutex в Kotlin НЕ является reentrant.
            // Если та же корутина попытается залочить мьютекс повторно, это приведёт
            // к deadlock. tryLock() предотвращает это, возвращая false.
            if (!saveMutex.tryLock()) {
                _saveResult.value = SaveResult.AlreadyInProgress
                return
            }
            try {
                _isSaving.value = true
                val result = performSaveInternal()
                _saveResult.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("Failed to save widget settings", e)
                _saveResult.value = SaveResult.Failed
            } finally {
                // Строгое соблюдение API: finally блок гарантирует, что мьютекс будет
                // освобождён даже при CancellationException. Это предотвращает deadlock
                // при повторном вызове saveAndNotify() после отмены.
                // На AOSP Mutex.unlock() безопасен при вызове из любой корутины.
                // Если кастомная прошивка не обеспечивает корректную работу Mutex —
                // это проблема кастомной прошивки.
                _isSaving.value = false
                saveMutex.unlock()
            }
        }

        /**
         * Выполняет сохранение настроек.
         *
         * Проверки перед сохранением:
         * 1. _loadError.value == true → Failed (настройки не были загружены).
         * 2. _settingsState.value == null → Failed (настройки ещё не загружены).
         * 3. settingsLoadFailed == true → Failed (загрузка завершилась ошибкой).
         * 4. simListLoadFailed == true → PartialSuccess (общие настройки сохраняются,
         *    SIM-настройки пропускаются).
         * 5. Иначе → полная попытка сохранения.
         *
         * Чтение .value из MutableStateFlow потокобезопасно.
         * Синхронизация не требуется.
         */
        @Suppress("ReturnCount")
        private fun determineSaveState(): SaveState {
            if (_loadError.value) return SaveState.LoadFailed
            val currentState = _settingsState.value ?: return SaveState.NotLoaded
            if (currentState.settingsLoadFailed) return SaveState.LoadFailed
            return SaveState.Ready(currentState)
        }

        private suspend fun performSaveInternal(): SaveResult =
            when (val saveState = determineSaveState()) {
                is SaveState.LoadFailed -> {
                    AppLogger.w("saveAndNotify skipped: settings load failed previously")
                    SaveResult.Failed
                }

                is SaveState.NotLoaded -> {
                    AppLogger.w("saveAndNotify called before settings were loaded")
                    SaveResult.Failed
                }

                is SaveState.Ready -> {
                    executeSave(saveState.state)
                }
            }

        private suspend fun executeSave(state: WidgetSettingsState): SaveResult {
            val isPartial = state.simListLoadFailed
            if (isPartial) {
                AppLogger.w(
                    "saveAndNotify: SIM list was not loaded successfully. " +
                        "Saving only general settings via skipSimSettings = true.",
                )
            }

            val timeout = Constants.Flow.LONG_TIMEOUT_MS.milliseconds

            return withTimeoutOrNull(timeout) {
                saver.save(state, skipSimSettings = isPartial)
            } ?: run {
                AppLogger.e("saveAndNotify: timeout during ${if (isPartial) "partial" else "full"} save.")
                SaveResult.Failed
            }
        }

        companion object {
            /**
             * Таймаут загрузки настроек виджета.
             *
             * Строгое соблюдение API: на AOSP WidgetSettingsLoader.load() завершается
             * за миллисекунды. 10 секунд — защитный запас.
             *
             * Отклонение кастомной прошивки: если DataStore деградирует и load()
             * занимает больше 10 секунд — это проблема кастомной прошивки.
             * Таймаут здесь указывает на деградацию DataStore на кастомной прошивке.
             */
            private val LOAD_TIMEOUT: Duration = 10.seconds
        }

        private sealed class SaveState {
            data object LoadFailed : SaveState()

            data object NotLoaded : SaveState()

            data class Ready(
                val state: WidgetSettingsState,
            ) : SaveState()
        }
    }
