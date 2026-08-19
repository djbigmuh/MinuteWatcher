package com.example.minutewatcher.feature.settings.ui

/**
 * Результат операции сохранения настроек виджета.
 *
 * Sealed interface гарантирует исчерпывающую обработку всех вариантов
 * в when-выражении. Компилятор гарантирует, что все варианты обработаны.
 *
 * Варианты:
 * - [Success] — полный успех.
 * - [PartialSuccess] — частичный успех (общие настройки сохранены,
 *   SIM-настройки пропущены).
 * - [AlreadyInProgress] — сохранение уже выполняется.
 * - [Failed] — полный провал.
 */
sealed interface SaveResult {
    /**
     * Полный успех сохранения. Все настройки сохранены.
     *
     * UI должен показать сообщение "Настройки сохранены".
     * После показа сообщения UI вызывает consumeSaveResult()
     * для сброса saveResult в null.
     */
    data object Success : SaveResult

    /**
     * Частичный успех: общие настройки сохранены, но SIM-специфичные
     * настройки пропущены (simListLoadFailed = true).
     *
     * UI должен показать сообщение "Общие настройки сохранены.
     * SIM-настройки пропущены (список SIM недоступен)."
     *
     * Строгое соблюдение API: на AOSP список SIM доступен всегда.
     * Частичный успех возможен только при деградации DataStore
     * на кастомной прошивке. Это проблема кастомной прошивки.
     */
    data class PartialSuccess(
        val skippedSimSettings: Boolean,
    ) : SaveResult

    /**
     * Partial failure: general settings were saved successfully, but at least one
     * of the SIM-specific settings failed to save due to an error (e.g., storage
     * degradation or SeedUnavailableException on custom firmware).
     *
     * @param message A human-readable message describing what failed.
     */
    data class PartialFailure(
        val message: String,
    ) : SaveResult

    /**
     * Сохранение уже выполняется. Это транзиентное состояние, которое
     * разрешается само: когда текущее сохранение завершится, saveResult
     * будет обновлён на Success/Failed/PartialFailure.
     *
     * UI должен показать сообщение "Сохранение уже выполняется" и не
     * предпринимать дополнительных действий.
     */
    data object AlreadyInProgress : SaveResult

    /**
     * Полный провал сохранения. Настройки не сохранены.
     *
     * Возможные причины:
     * - Настройки не были загружены (_loadError = true).
     * - _settingsState = null (настройки ещё не загружены).
     * - settingsLoadFailed = true (загрузка завершилась ошибкой).
     * - Исключение при сохранении.
     * - Таймаут при сохранении.
     *
     * UI должен показать сообщение "Не удалось сохранить настройки".
     */
    data object Failed : SaveResult
}
