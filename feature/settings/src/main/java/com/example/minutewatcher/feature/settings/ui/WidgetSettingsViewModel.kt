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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        private val _loadError = MutableStateFlow(false)
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
        // При вызове loadSettings() предыдущий Job отменяется через cancel().
        // Новый Job создаётся в viewModelScope.launch.
        //
        // Отмена кооперативная: старый Job обрабатывает отмену на ближайшей
        // точке приостановки или при ensureActive(). После этого loadMutex
        // освобождается и новый Job может его залочить.
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
         * loadJob?.cancel() отменяет предыдущую корутину. Новая корутина
         * ожидает освобождения loadMutex (старая корутина освободит его
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
            // _loadError сбрасывается в false при начале новой загрузки.
            // Если предыдущая загрузка завершилась ошибкой, вызов retryLoad()
            // сбрасывает ошибку. Если новая загрузка тоже завершится ошибкой,
            // _loadError будет установлен в true снова.
            // Это корректное поведение: пользователь видит актуальное состояние.
            _loadError.value = false
            // loadMutex обеспечивает сериализацию загрузок. При отмене предыдущей
            // корутины (loadJob?.cancel()) Mutex освобождается автоматически
            // (Kotlin Mutex.release при CancellationException). Новая корутина
            // захватывает Mutex и выполняет загрузку. Это гарантирует, что
            // только одна загрузка выполняется одновременно.
            loadJob?.cancel()
            // viewModelScope привязан к lifecycle ViewModel.
            // При уничтожении ViewModel (onCleared) все корутины в viewModelScope
            // отменяются. Это предотвращает утечки и обращение к уничтоженному ViewModel.
            //
            // Строгое соблюдение API: viewModelScope гарантированно отменяется
            // при onCleared(). Это стандартная best practice для ViewModel.
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

        /**
         * Сбрасывает результат сохранения в null.
         * Вызывается UI после обработки результата (показа Snackbar).
         * Предотвращает повторный показ Snackbar при рекомпозиции.
         *
         * LaunchedEffect keyed on saveResult перезапускается при изменении
         * saveResult. После consumeSaveResult() saveResult становится null,
         * эффект перезапускается с null и ничего не делает.
         */
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
            /**
             * Строгое соблюдение API: на AOSP save() завершается за миллисекунды.
             * Таймаут 10 с — защитный запас. Таймаут здесь указывает на
             * деградацию DataStore на кастомной прошивке.
             */
            return withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                val result = saver.save(validatedState, skipSimSettings = true)
                if (result is SaveResult.PartialSuccess || result is SaveResult.Success) {
                    SaveResult.PartialFailure("SIM settings skipped")
                } else {
                    result
                }
            } ?: run {
                AppLogger.e("saveAndNotify: timeout during partial save. Possible DataStore degradation.")
                SaveResult.Failed
            }
        }

        private suspend fun saveFullSettings(state: WidgetSettingsState): SaveResult {
            val validatedState = validateSettings(state)
            /**
             * Строгое соблюдение API: на AOSP save() завершается за миллисекунды.
             * Таймаут 10 с — защитный запас. Таймаут здесь указывает на
             * деградацию DataStore на кастомной прошивке.
             */
            return withTimeoutOrNull(Constants.Flow.LONG_TIMEOUT_MS.milliseconds) {
                saver.save(validatedState)
            } ?: run {
                AppLogger.e("saveAndNotify: timeout during full save. Possible DataStore degradation.")
                SaveResult.Failed
            }
        }

        /**
         * Валидирует настройки перед сохранением.
         *
         * Строгое соблюдение API: значения coerceIn гарантируют, что виджет
         * получит корректные параметры даже если UI передал некорректные данные
         * (например, из-за бага в слайдере).
         *
         * Эвристика в пределах критерия надёжности: coerceIn молча приводит
         * значения к допустимому диапазону. Это допустимо, т.к. альтернатива
         * (бросить исключение) приведёт к потере всех настроек пользователя.
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
             * Строгое соблюдение API: на AOSP WidgetSettingsLoader.load() завершается
             * за миллисекунды. 10 секунд — защитный запас.
             *
             * Отклонение кастомной прошивки: если DataStore деградирует и load()
             * занимает больше 10 секунд — это проблема кастомной прошивки.
             * Таймаут здесь указывает на деградацию DataStore на кастомной прошивке.
             */
            private val LOAD_TIMEOUT: Duration = 10.seconds
        }
    }
