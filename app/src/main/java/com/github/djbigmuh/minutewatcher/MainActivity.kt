package com.github.djbigmuh.minutewatcher

import android.Manifest
import android.content.Intent
import android.content.LocusId
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.github.djbigmuh.minutewatcher.controller.MainPermissionController
import com.github.djbigmuh.minutewatcher.core.common.util.AppLogger
import com.github.djbigmuh.minutewatcher.core.common.util.PermissionManager
import com.github.djbigmuh.minutewatcher.core.data.NotificationHelper
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MinuteWatcherTheme
import com.github.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import com.github.djbigmuh.minutewatcher.core.model.Constants.IntentExtras
import com.github.djbigmuh.minutewatcher.core.resources.R
import com.github.djbigmuh.minutewatcher.feature.main.MainViewModel
import com.github.djbigmuh.minutewatcher.feature.main.SimCardScreen
import com.github.djbigmuh.minutewatcher.feature.main.SimCardScreenActions
import com.github.djbigmuh.minutewatcher.feature.main.ui.theme.rememberIsDarkTheme
import com.github.djbigmuh.minutewatcher.feature.widget.MinutesGlanceWidget
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var permissionManager: PermissionManager

    @Inject
    lateinit var permissionStateNotifier: PermissionStateNotifier

    @Inject
    lateinit var permissionController: MainPermissionController

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var widgetDataChangeDispatcher: WidgetDataChangeDispatcher

    @Inject
    lateinit var navigator: MainActivityNavigator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState != null) {
            lastPermissionState = savedInstanceState.getInt(KEY_LAST_PERMISSION_STATE, -1)
        }

        MainActivityThemeObserver.observe(lifecycleScope, lifecycle, viewModel) {
            notificationHelper.ensureChannels()
            widgetDataChangeDispatcher.notifyDataChanged("locale restore")
        }
        setupLocusContext()

        setContent {
            AppContent()
        }

        permissionHandler.checkPermissionsAndInit()
        handleIntent(intent)
    }

    private fun setupLocusContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setLocusContext(LocusId("main_screen"), null)
        }
    }

    @Composable
    private fun AppContent() {
        val themeState by viewModel.appTheme.collectAsStateWithLifecycle()
        val darkTheme = rememberIsDarkTheme(themeState)

        MinuteWatcherTheme(darkTheme = darkTheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                val activity = this@MainActivity
                val actions =
                    remember(viewModel, navigator, activity) {
                        SimCardScreenActions(
                            onOpenSettings = { navigator.openWidgetSettings(activity) },
                            onAddWidget = { navigator.addWidgetToHomeScreen(activity) },
                            onThemeChange = { theme -> viewModel.setTheme(theme) },
                            onLanguageChange = { lang -> handleLanguageChange(lang) },
                            onGrantPhonePermission = {
                                viewModel.setPhonePermissionRequested()
                                permissionHandler.phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                            },
                            onShowUsageAccessDialog = {
                                viewModel.setUsageAccessDialog(true)
                            },
                            onOpenUsageAccessSettings = {
                                navigator.openUsageAccessSettings(activity)
                            },
                            onOpenAppSettings = {
                                navigator.openAppSettings(activity)
                            },
                            onGrantNotificationPermission = {
                                permissionHandler.requestNotificationPermission()
                            },
                            onShowAbout = {
                                viewModel.setShowAboutDialog(true)
                            },
                            onEmailAuthor = {
                                viewModel.setShowAboutDialog(false)
                                navigator.openEmailComposer(activity, activity.getString(R.string.about_author_email))
                            },
                        )
                    }
                SimCardScreen(
                    viewModel = viewModel,
                    actions = actions,
                )
            }
        }
    }

    private fun handleLanguageChange(lang: String?) {
        val appLocale: LocaleListCompat =
            if (lang == null) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(lang)
            }
        AppCompatDelegate.setApplicationLocales(appLocale)
        notificationHelper.ensureChannels()
        viewModel.setLanguage(lang)
        widgetDataChangeDispatcher.notifyDataChanged("language change")
        lifecycleScope.launch {
            @Suppress("TooGenericExceptionCaught")
            try {
                MinutesGlanceWidget().updateAll(this@MainActivity)
            } catch (e: Exception) {
                AppLogger.e("Failed to update widgets on language change", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val shortcutId = it.getStringExtra(IntentExtras.SHORTCUT_ID)
            if (!shortcutId.isNullOrEmpty()) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    ShortcutManagerCompat
                        .reportShortcutUsed(this, shortcutId)
                } catch (e: Exception) {
                    // Operation must complete without propagating exception for UI interaction stability.
                    AppLogger.w("Failed to report shortcut used: $shortcutId", e)
                }
            }
            val simSlot = it.getIntExtra(IntentExtras.SIM_SLOT, -1)
            if (simSlot != -1) {
                viewModel.highlightSlot(simSlot)
            }
        }
    }

    private val permissionHandler = PermissionHandler()

    private inner class PermissionHandler {
        val phoneLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                handlePhoneResult(granted)
            }

        val notificationLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                handleNotificationResult(granted)
            }

        private fun handlePhoneResult(granted: Boolean) {
            val permanentlyDenied =
                !granted &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)
            viewModel.setPhonePermissionPermanentlyDenied(permanentlyDenied)
            checkPermissionChanges()
        }

        private fun handleNotificationResult(granted: Boolean) {
            // API 33+ required for POST_NOTIFICATIONS runtime permission.
            // See: https://developer.android.com/training/notify-user/runtime-permissions
            // minSdk is 26, so this branch is reachable and required.
            val permanentlyDenied =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !granted &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            viewModel.setNotificationPermissionPermanentlyDenied(permanentlyDenied)
            checkPermissionChanges()
        }

        fun checkPermissionsAndInit() {
            permissionController.checkPermissionsAndInit(
                scope = lifecycleScope,
                isInitialSetupCompleted = viewModel.isInitialSetupCompleted,
                appSettingsLoaded = viewModel.appSettings,
                lastPermissionState = lastPermissionState,
                onShowUsageAccessDialog = { show ->
                    viewModel.setUsageAccessDialog(show)
                    if (show) viewModel.markUsageAccessDialogShown()
                },
                onRequestPhonePermission = {
                    viewModel.setPhonePermissionRequested()
                    phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                },
                onCheckNotifications = { checkNotifications(it) },
            )
        }

        fun checkNotifications(autoRequest: Boolean) {
            permissionController.checkNotifications(
                scope = lifecycleScope,
                appSettingsLoaded = viewModel.appSettings,
                autoRequest = autoRequest,
                onRequest = { requestNotificationPermission() },
            )
        }

        fun requestNotificationPermission() {
            // API 33+ required for POST_NOTIFICATIONS runtime permission.
            // See: https://developer.android.com/training/notify-user/runtime-permissions
            // minSdk is 26, so this branch is reachable and required.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                viewModel.setNotificationPermissionRequested()
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_LAST_PERMISSION_STATE, lastPermissionState)
    }

    override fun onResume() {
        super.onResume()

        viewModel.onActivityResume()

        checkPermissionChanges()
    }

    private var lastPermissionState: Int = -1

    private fun checkPermissionChanges() {
        val currentState =
            (if (permissionManager.hasPhoneStatePermission()) STATE_BIT_PHONE else 0) or
                (if (permissionManager.hasUsageStatsPermission()) STATE_BIT_USAGE else 0) or
                (if (permissionManager.hasNotificationPermission()) STATE_BIT_NOTIFICATION else 0)

        if (lastPermissionState != -1 && (currentState != lastPermissionState)) {
            permissionStateNotifier.notifyChanged()
            // If permissions changed, we might need a fresh setup run.
            // KEEP policy in WorkScheduler will avoid duplication if it's already running.
            permissionHandler.checkPermissionsAndInit()
        }
        lastPermissionState = currentState
    }

    companion object {
        private const val KEY_LAST_PERMISSION_STATE = "last_permission_state"
        private const val STATE_BIT_PHONE = 1
        private const val STATE_BIT_USAGE = 2
        private const val STATE_BIT_NOTIFICATION = 4
    }
}
