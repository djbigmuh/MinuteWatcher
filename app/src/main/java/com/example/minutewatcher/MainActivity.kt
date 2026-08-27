package com.example.minutewatcher

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.LocusId
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.IoDispatcher
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.WorkScheduler
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants.IntentExtras
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.MainViewModel
import com.example.minutewatcher.feature.main.SimCardScreen
import com.example.minutewatcher.feature.main.SimCardScreenActions
import com.example.minutewatcher.feature.main.ui.theme.MinuteWatcherTheme
import com.example.minutewatcher.feature.widget.MinutesGlanceWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var permissionManager: PermissionManager

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    @Inject lateinit var permissionStateNotifier: PermissionStateNotifier

    @Inject lateinit var workScheduler: WorkScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState != null) {
            lastPermissionState = savedInstanceState.getInt(KEY_LAST_PERMISSION_STATE, -1)
        }

        setupObservers()
        setupLocusContext()

        setContent {
            AppContent()
        }

        permissionHandler.checkPermissionsAndInit()
        handleIntent(intent)
    }

    private fun setupObservers() {
        observeAppTheme()
        observeAppLanguage()
    }

    private fun setupLocusContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setLocusContext(LocusId("main_screen"), null)
        }
    }

    private fun observeAppTheme() {
        lifecycleScope.launch {
            viewModel.appTheme.collect { theme ->
                val mode =
                    when (theme) {
                        AppTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                        AppTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                        AppTheme.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }

    private fun observeAppLanguage() {
        lifecycleScope.launch {
            viewModel.appLanguage.collect { lang ->
                val appLocale: LocaleListCompat =
                    if (lang == null) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(lang)
                    }
                if (AppCompatDelegate.getApplicationLocales() != appLocale) {
                    AppCompatDelegate.setApplicationLocales(appLocale)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AppContent() {
        val themeState by viewModel.appTheme.collectAsState()
        val darkTheme =
            when (themeState) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.FOLLOW_SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

        MinuteWatcherTheme(darkTheme = darkTheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                val actions =
                    remember(viewModel) {
                        SimCardScreenActions(
                            onOpenSettings = { openWidgetSettings() },
                            onAddWidget = { addWidgetToHomeScreen() },
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
                                openUsageAccessSettings()
                            },
                            onOpenAppSettings = {
                                openAppSettings()
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

    private fun openWidgetSettings() {
        startActivity(Intent(this, WidgetSettingsActivity::class.java))
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
            if (granted) {
                // WorkManager.enqueue() performs disk I/O internally.
                // Injected ioDispatcher prevents potential ANR on slow storage.
                lifecycleScope.launch(ioDispatcher) {
                    try {
                        workScheduler.scheduleInitialSetup()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e(
                            "MainActivity: Failed to schedule background work after phone permission granted",
                            e,
                        )
                    }
                }
            }
            permissionStateNotifier.notifyChanged()
        }

        private fun handleNotificationResult(
            @Suppress("UNUSED_PARAMETER") granted: Boolean,
        ) {
            // API 33+ required for POST_NOTIFICATIONS runtime permission.
            // See: https://developer.android.com/training/notify-user/runtime-permissions
            // minSdk is 26, so this branch is reachable and required.
            permissionStateNotifier.notifyChanged()
        }

        fun checkPermissionsAndInit() {
            lifecycleScope.launch {
                val settings = viewModel.appSettings.first()
                if (permissionManager.hasPhoneStatePermission()) {
                    // WorkManager.enqueue() performs disk I/O internally.
                    // Injected ioDispatcher prevents potential ANR on slow storage.
                    launch(ioDispatcher) {
                        try {
                            workScheduler.scheduleInitialSetup()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("MainActivity: Failed to schedule background work during init", e)
                        }
                    }

                    if (!permissionManager.hasUsageStatsPermission()) {
                        viewModel.setUsageAccessDialog(true)
                    } else {
                        checkNotifications(autoRequest = true)
                    }
                } else if (settings.wasPhonePermissionRequestedBefore) {
                    // Permission not granted, but requested before.
                    // The derived UI state will handle "Permanently Denied" vs "Denied".
                } else {
                    // First run: permission not yet requested.
                    viewModel.setPhonePermissionRequested()
                    phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
            }
        }

        fun checkNotifications(autoRequest: Boolean) {
            lifecycleScope.launch {
                if (!permissionManager.hasNotificationPermission()) {
                    val settings = viewModel.appSettings.first()
                    if (autoRequest && !settings.notificationPermissionRequested) {
                        requestNotificationPermission()
                    }
                }
            }
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

    private fun openAppSettings() {
        startActivity(NavigationIntentFactory.getAppSettingsIntent(this))
    }

    private fun openUsageAccessSettings() {
        // Приоритет: экран «Доступ к данным об использовании» для конкретного приложения.
        try {
            startActivity(NavigationIntentFactory.getUsageAccessSettingsIntent(this))
            return
        } catch (e: Exception) {
            AppLogger.w("Package-scoped usage access settings unavailable, trying generic list", e)
        }
        // Fallback: общий список приложений с доступом к данным об использовании.
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        } catch (e: Exception) {
            AppLogger.e("Usage access settings not found, opening app details", e)
        }
        openAppSettings()
    }

    private fun addWidgetToHomeScreen() {
        val appWidgetManager = getSystemService(AppWidgetManager::class.java)
        val myProvider = ComponentName(this, MinutesGlanceWidgetReceiver::class.java)

        val existingIds = appWidgetManager.getAppWidgetIds(myProvider)
        if (existingIds.isNotEmpty()) {
            Toast.makeText(this, getString(R.string.widget_already_added), Toast.LENGTH_SHORT).show()
            return
        }

        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            val callbackIntent = Intent(this, PinWidgetReceiver::class.java)
            val successCallback =
                PendingIntent.getBroadcast(
                    this,
                    0,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
        } else {
            Toast.makeText(this, getString(R.string.widget_pin_unsupported), Toast.LENGTH_SHORT).show()
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

        checkPermissionChanges()

        if (phoneGranted && permissionManager.hasUsageStatsPermission()) {
            permissionHandler.checkNotifications(autoRequest = false)
        }
    }

    private var lastPermissionState: Int = -1

    private fun checkPermissionChanges() {
        val currentState =
            (if (permissionManager.hasPhoneStatePermission()) STATE_BIT_PHONE else 0) or
                (if (permissionManager.hasUsageStatsPermission()) STATE_BIT_USAGE else 0) or
                (if (permissionManager.hasNotificationPermission()) STATE_BIT_NOTIFICATION else 0)

        if (lastPermissionState == -1 || (currentState != lastPermissionState)) {
            permissionStateNotifier.notifyChanged()
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
