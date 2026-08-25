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
 * @param initialState Начальное состояние.
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
     */
    protected fun reduce(reducer: S.() -> S) {
        _state.update { it.reducer() }
    }
}
