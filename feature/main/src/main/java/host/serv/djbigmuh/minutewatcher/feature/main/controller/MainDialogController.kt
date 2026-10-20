package host.serv.djbigmuh.minutewatcher.feature.main.controller

import androidx.lifecycle.SavedStateHandle
import host.serv.djbigmuh.minutewatcher.feature.main.AdjustDialogData
import host.serv.djbigmuh.minutewatcher.feature.main.DayDialogData
import host.serv.djbigmuh.minutewatcher.feature.main.LimitDialogData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * MainDialogController manages the state of dialogs on the main screen.
 *
 * **Thread Safety:** Safe to call from any coroutine context.
 * **SavedStateHandle:** Persists dialog state via [SavedStateHandle]
 * (survives configuration changes and process death).
 * **Invariant:** Showing the onboarding dialog is performed at most once per lifecycle of the
 * controller instance. Hiding the dialog prevents any subsequent showing until the ViewModel
 * is recreated.
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
        val showAboutDialog = savedStateHandle.getStateFlow(KEY_ABOUT_DIALOG, false)

        private val onboardingPromptClaimed = AtomicBoolean(false)

        /**
         * Claims the single permission/right to show the onboarding dialog prompt.
         *
         * @return True if this is the first claim call for this controller instance, false otherwise.
         */
        fun claimOnboardingPrompt(): Boolean = onboardingPromptClaimed.compareAndSet(false, true)

        /**
         * Sets the data for the limit editing dialog.
         *
         * @param data Dialog data or null to dismiss.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **SavedStateHandle:** Persists state via [SavedStateHandle].
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
         * **SavedStateHandle:** Persists state via [SavedStateHandle].
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
         * **SavedStateHandle:** Persists state via [SavedStateHandle].
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
         * **SavedStateHandle:** Persists state via [SavedStateHandle].
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
         * **SavedStateHandle:** Persists state via [SavedStateHandle].
         */
        fun setUsageAccessDialog(show: Boolean) {
            savedStateHandle[KEY_USAGE_ACCESS_DIALOG] = show
        }

        /**
         * Shows or hides the about application dialog.
         *
         * @param show True to show, false to hide.
         *
         * **Thread Safety:** Safe to call from any coroutine context.
         * **SavedStateHandle:** Persists state via [SavedStateHandle].
         */
        fun setShowAboutDialog(show: Boolean) {
            savedStateHandle[KEY_ABOUT_DIALOG] = show
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
            if (!show) {
                onboardingPromptClaimed.set(true)
            }
            _showOnboardingDialog.value = show
        }

        companion object {
            const val KEY_LIMIT_DIALOG = "limit_dialog"
            const val KEY_DAY_DIALOG = "day_dialog"
            const val KEY_ADJUST_DIALOG = "adjust_dialog"
            const val KEY_TRAFFIC_LIMITATION_DIALOG = "traffic_limitation_dialog"
            const val KEY_USAGE_ACCESS_DIALOG = "usage_access_dialog"
            const val KEY_ABOUT_DIALOG = "about_dialog"
        }
    }
