package com.example.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.feature.main.AdjustDialogData
import com.example.minutewatcher.feature.main.DayDialogData
import com.example.minutewatcher.feature.main.LimitDialogData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

class MainDialogController
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
    ) {
        val showLimitDialog = savedStateHandle.getStateFlow<LimitDialogData?>("limit_dialog", null)
        val showDayDialog = savedStateHandle.getStateFlow<DayDialogData?>("day_dialog", null)
        val showAdjustDialog = savedStateHandle.getStateFlow<AdjustDialogData?>("adjust_dialog", null)
        val showTrafficLimitationDialog = savedStateHandle.getStateFlow("traffic_limitation_dialog", false)
        val showUsageAccessDialog = savedStateHandle.getStateFlow("usage_access_dialog", false)

        fun setLimitDialog(data: LimitDialogData?) {
            savedStateHandle["limit_dialog"] = data
        }

        fun setDayDialog(data: DayDialogData?) {
            savedStateHandle["day_dialog"] = data
        }

        fun setAdjustDialog(data: AdjustDialogData?) {
            savedStateHandle["adjust_dialog"] = data
        }

        fun setTrafficLimitationDialog(show: Boolean) {
            savedStateHandle["traffic_limitation_dialog"] = show
        }

        fun setUsageAccessDialog(show: Boolean) {
            savedStateHandle["usage_access_dialog"] = show
        }

        private val _showOnboardingDialog = MutableStateFlow(false)
        val showOnboardingDialog: StateFlow<Boolean> = _showOnboardingDialog.asStateFlow()

        fun setShowOnboardingDialog(show: Boolean) {
            _showOnboardingDialog.value = show
        }
    }
