package com.example.minutewatcher.feature.settings.ui

/**
 * Результат операции сохранения настроек виджета.
 *
 * Sealed interface гарантирует исчерпывающую обработку всех вариантов
 * в when-выражении. Компилятор гарантирует, что все варианты обработаны.
 */
sealed interface SaveResult {
    /**
     * Полный успех сохранения. Все настройки сохранены.
     *
     * **Важно:** это одноразовое событие для Snackbar.
     * UI должен показать сообщение "Настройки сохранены".
     * После показа сообщения UI вызывает consumeSaveResult()
     * для сброса saveResult в null.
     */
    data object Success : SaveResult

    /**
     * Частичный успех: общие настройки сохранены, но SIM-специфичные
     * настройки пропущены (simListLoadFailed = true).
     *
     * **Важно:** это одноразовое событие для Snackbar.
     * UI должен показать сообщение "Общие настройки сохранены.
     * SIM-настройки пропущены (список SIM недоступен)."
     */
    data class PartialSuccess(
        val skippedSimSettings: Boolean,
    ) : SaveResult

    /**
     * Частичный сбой: общие настройки сохранены успешно, но хотя бы одна
     * из SIM-специфичных настроек не была сохранена из-за ошибки.
     *
     * **Важно:** это одноразовое событие для Snackbar.
     */
    data class PartialFailure(
        val message: String,
    ) : SaveResult

    /**
     * Полный сбой сохранения. Настройки не сохранены.
     *
     * **Важно:** это одноразовое событие для Snackbar.
     */
    data class Failed(
        val message: String? = null,
    ) : SaveResult
}
