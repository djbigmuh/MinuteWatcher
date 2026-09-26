package com.example.minutewatcher.feature.main

import androidx.annotation.StringRes
import com.example.minutewatcher.core.model.SimCardUiModel

sealed interface SimCardListState {
    data object Loading : SimCardListState

    data class Success(
        val simCards: List<SimCardUiModel>,
    ) : SimCardListState

    data class Error(
        @StringRes val messageRes: Int,
    ) : SimCardListState

    data object Empty : SimCardListState
}
