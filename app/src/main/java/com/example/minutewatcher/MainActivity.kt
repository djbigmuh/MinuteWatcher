package com.example.minutewatcher

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
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.minutewatcher.controller.MainPermissionController
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.model.Constants.IntentExtras
import com.example.minutewatcher.feature.main.MainViewModel
import com.example.minutewatcher.feature.main.SimCardScreen
import com.example.minutewatcher.feature.main.SimCardScreenActions
import com.example.minutewatcher.feature.main.ui.theme.MinuteWatcherTheme
import com.example.minutewatcher.feature.main.ui.theme.rememberIsDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var permissionManager: PermissionManager

    @Inject lateinit var permissionStateNotifier: PermissionStateNotifier

    @Inject
    lateinit var permissionController: MainPermissionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState != null) {
            lastPermissionState = savedInstanceState.getInt(KEY_LAST_PERMISSION_STATE, -1)
        }

        MainActivityThemeObserver.observe(lifecycleScope, lifecycle, viewModel)
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
                val actions =
                    remember(viewModel) {
                        SimCardScreenActions(
                            onOpenSettings = { MainActivityNavigator.openWidgetSettings(this@MainActivity) },
                            onAddWidget = { MainActivityNavigator.addWidgetToHomeScreen(this@MainActivity) },
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
                                MainActivityNavigator.openUsageAccessSettings(this@MainActivity)
                            },
                            onOpenAppSettings = {
                                MainActivityNavigator.openAppSettings(this@MainActivity)
                            },
                            onGrantNotificationPermission = {
                                permissionHandler.requestNotificationPermission()
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
        viewModel.setLanguage(lang)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val simSlot = it.getIntExtra(IntentExtras.SIM_SLOT, -1)
            if (simSlot != -1) {
                viewModel.highlightSlot(simSlot)
            }
        }
    }

    private val permissionHandler = PermissionHandler()

    private inner class PermissionHandler {
        val phoneLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                handlePhoneResult()
            }

        val notificationLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                handleNotificationResult()
            }

        private fun handlePhoneResult() {
            checkPermissionChanges()
        }

        private fun handleNotificationResult() {
            // API 33+ required for POST_NOTIFICATIONS runtime permission.
            // See: https://developer.android.com/training/notify-user/runtime-permissions
            // minSdk is 26, so this branch is reachable and required.
            checkPermissionChanges()
        }

        fun checkPermissionsAndInit() {
            permissionController.checkPermissionsAndInit(
                scope = lifecycleScope,
                viewModel = viewModel,
                lastPermissionState = lastPermissionState,
                onShowUsageAccessDialog = { viewModel.setUsageAccessDialog(it) },
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
                viewModel = viewModel,
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

        val phoneGranted = permissionManager.hasPhoneStatePermission()
        val usageGranted = permissionManager.hasUsageStatsPermission()

        checkPermissionChanges()

        if (phoneGranted) {
            if (!usageGranted) {
                viewModel.setUsageAccessDialog(true)
            } else {
                permissionHandler.checkNotifications(autoRequest = false)
            }
        }
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
