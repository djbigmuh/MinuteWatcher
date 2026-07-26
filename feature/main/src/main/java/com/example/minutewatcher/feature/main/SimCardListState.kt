package com.example.minutewatcher.feature.main

import com.example.minutewatcher.core.model.SimCardUiModel

sealed class SimCardListState {
    object Loading : SimCardListState()
    data class Success(val simCards: List<SimCardUiModel>) : SimCardListState()
    data class Error(val message: String) : SimCardListState()
    object Empty : SimCardListState()
}
