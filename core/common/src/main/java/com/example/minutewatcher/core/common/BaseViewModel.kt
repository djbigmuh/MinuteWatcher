package com.example.minutewatcher.core.common

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Базовый класс для ViewModel с состоянием.
 *
 * **Важно:** этот класс НЕ использует SharedFlow для событий.
 * Текущий код использует StateFlow, который гарантирует доставку последнего значения.
 * Переход на SharedFlow может привести к потере событий.
 *
 * **Важно:** этот класс обрабатывает `null` состояние.
 * Текущий код использует `_settingsState.update { current -> current?.let(transform) ?: current }`.
 *
 * @param initialState Начальное состояние. Может быть `null`.
 */
abstract class BaseViewModel<S>(
    initialState: S,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState)

    /**
     * Текущее состояние ViewModel как [StateFlow].
     */
    val state: StateFlow<S> = _state.asStateFlow()

    /**
     * Обновляет состояние.
     *
     * **Важно:** этот метод обрабатывает `null` состояние.
     * Если текущее состояние `null`, оно остаётся `null`.
     */
    protected fun reduce(reducer: S.() -> S) {
        _state.update { current ->
            current?.let(reducer) ?: current
        }
    }
}
