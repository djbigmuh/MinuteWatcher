package host.serv.djbigmuh.minutewatcher.core.common

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Base class for ViewModel with state.
 *
 * **Important:** this class does NOT use SharedFlow for events.
 * Current code uses StateFlow, which guarantees delivery of the latest value.
 * Switching to SharedFlow might lead to event loss.
 *
 * @param initialState Initial state.
 */
open class BaseViewModel<S>(
    initialState: S,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState)

    /**
     * Current ViewModel state as [StateFlow].
     */
    val state: StateFlow<S> = _state.asStateFlow()

    /**
     * Updates the state.
     */
    protected fun reduce(reducer: S.() -> S) = _state.update { it.reducer() }

    /**
     * Atomically updates the state and returns the result.
     */
    protected fun updateAndGet(reducer: S.() -> S): S = _state.updateAndGet { it.reducer() }
}
