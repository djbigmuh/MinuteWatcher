package com.example.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import com.example.minutewatcher.feature.main.AdjustDialogData
import com.example.minutewatcher.feature.main.DayDialogData
import com.example.minutewatcher.feature.main.LimitDialogData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * MainDialogController manages the state of dialogs on the main screen.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **AOSP DataStore:** Persists dialog state via [SavedStateHandle].
 *
 * @property savedStateHandle SavedStateHandle for preserving dialog state across process death.
 */
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

        /**
         * Sets the data for the limit editing dialog.
         *
         * @param data Dialog data or null to dismiss.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Persists state via [SavedStateHandle].
         */
        fun setLimitDialog(data: LimitDialogData?) {
            savedStateHandle[KEY_LIMIT_DIALOG] = data
        }

        /**
         * Sets the data for the day editing dialog.
         *
         * @param data Dialog data or null to dismiss.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Persists state via [SavedStateHandle].
         */
        fun setDayDialog(data: DayDialogData?) {
            savedStateHandle[KEY_DAY_DIALOG] = data
        }

        /**
         * Sets the data for the usage adjustment dialog.
         *
         * @param data Dialog data or null to dismiss.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Persists state via [SavedStateHandle].
         */
        fun setAdjustDialog(data: AdjustDialogData?) {
            savedStateHandle[KEY_ADJUST_DIALOG] = data
        }

        /**
         * Shows or hides the traffic limitation information dialog.
         *
         * @param show True to show, false to hide.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Persists state via [SavedStateHandle].
         */
        fun setTrafficLimitationDialog(show: Boolean) {
            savedStateHandle[KEY_TRAFFIC_LIMITATION_DIALOG] = show
        }

        /**
         * Shows or hides the usage access request dialog.
         *
         * @param show True to show, false to hide.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **AOSP DataStore:** Persists state via [SavedStateHandle].
         */
        fun setUsageAccessDialog(show: Boolean) {
            savedStateHandle[KEY_USAGE_ACCESS_DIALOG] = show
        }

        private val _showOnboardingDialog = MutableStateFlow(false)
        val showOnboardingDialog: StateFlow<Boolean> = _showOnboardingDialog.asStateFlow()

        /**
         * Shows or hides the onboarding dialog.
         *
         * @param show True to show, false to hide.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         */
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
