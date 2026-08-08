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
        val showLimitDialog = savedStateHandle.getStateFlow<LimitDialogData?>(KEY_LIMIT_DIALOG, null)
        val showDayDialog = savedStateHandle.getStateFlow<DayDialogData?>(KEY_DAY_DIALOG, null)
        val showAdjustDialog = savedStateHandle.getStateFlow<AdjustDialogData?>(KEY_ADJUST_DIALOG, null)
        val showTrafficLimitationDialog = savedStateHandle.getStateFlow(KEY_TRAFFIC_LIMITATION_DIALOG, false)
        val showUsageAccessDialog = savedStateHandle.getStateFlow(KEY_USAGE_ACCESS_DIALOG, false)

        fun setLimitDialog(data: LimitDialogData?) {
            savedStateHandle[KEY_LIMIT_DIALOG] = data
        }

        fun setDayDialog(data: DayDialogData?) {
            savedStateHandle[KEY_DAY_DIALOG] = data
        }

        fun setAdjustDialog(data: AdjustDialogData?) {
            savedStateHandle[KEY_ADJUST_DIALOG] = data
        }

        fun setTrafficLimitationDialog(show: Boolean) {
            savedStateHandle[KEY_TRAFFIC_LIMITATION_DIALOG] = show
        }

        fun setUsageAccessDialog(show: Boolean) {
            savedStateHandle[KEY_USAGE_ACCESS_DIALOG] = show
        }

        private val _showOnboardingDialog = MutableStateFlow(false)
        val showOnboardingDialog: StateFlow<Boolean> = _showOnboardingDialog.asStateFlow()

        fun setShowOnboardingDialog(show: Boolean) {
            _showOnboardingDialog.value = show
        }

        companion object {
            const val KEY_LIMIT_DIALOG = "limit_dialog"
            const val KEY_DAY_DIALOG = "day_dialog"
            const val KEY_ADJUST_DIALOG = "adjust_dialog"
            const val KEY_TRAFFIC_LIMITATION_DIALOG = "traffic_limitation_dialog"
            const val KEY_USAGE_ACCESS_DIALOG = "usage_access_dialog"
        }
    }
