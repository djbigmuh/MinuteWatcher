package host.serv.djbigmuh.minutewatcher.feature.main

import androidx.annotation.StringRes
import host.serv.djbigmuh.minutewatcher.core.model.SimCardUiModel

sealed interface SimCardListState {
    data object Loading : SimCardListState

    data class Success(
        val simCards: List<SimCardUiModel>,
    ) : SimCardListState

    enum class ErrorKind {
        REFRESH,
        SIM_DISCOVERY,
    }

    data class Error(
        @StringRes val messageRes: Int,
        val kind: ErrorKind = ErrorKind.REFRESH,
    ) : SimCardListState

    data object Empty : SimCardListState
}
