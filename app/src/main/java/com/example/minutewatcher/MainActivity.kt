package com.example.minutewatcher

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.LocusId
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.example.minutewatcher.core.data.WorkScheduler
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.core.android.util.PermissionManager
import com.example.minutewatcher.feature.main.MainViewModel
import com.example.minutewatcher.feature.main.SimCardScreen
import com.example.minutewatcher.feature.main.ui.theme.MinuteWatcherTheme
import com.example.minutewatcher.feature.widget.MinutesGlanceWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var permissionManager: PermissionManager

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject lateinit var permissionStateNotifier: PermissionStateNotifier

    @Inject lateinit var workScheduler: WorkScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        lifecycleScope.launch {
            viewModel.appTheme.collect { theme ->
                AppCompatDelegate.setDefaultNightMode(theme.value)
            }
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setLocusContext(LocusId("main_screen"), null)
        }

        setContent {
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
                    SimCardScreen(
                        viewModel = viewModel,
                        onOpenSettings = {
                            openWidgetSettings()
                        },
                        onAddWidget = { addWidgetToHomeScreen() },
                        onThemeChange = { theme ->
                            viewModel.setTheme(theme)
                        },
                        onLanguageChange = { lang ->
                            val appLocale: LocaleListCompat =
                                if (lang == null) {
                                    LocaleListCompat.getEmptyLocaleList()
                                } else {
                                    LocaleListCompat.forLanguageTags(lang)
                                }
                            AppCompatDelegate.setApplicationLocales(appLocale)
                            viewModel.setLanguage(lang)
                        },
                        onGrantPhonePermission = {
                            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        },
                        onOpenUsageSettings = {
                            viewModel.openUsageAccessDialog()
                        },
                        onOpenAppSettings = {
                            openAppSettings()
                        },
                        onGrantNotificationPermission = {
                            requestNotificationPermission()
                        },
                    )
                }
            }
        }

        checkPermissionsAndInit()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val simSlot = it.getIntExtra("sim_slot", -1)
            if (simSlot != -1) {
                viewModel.highlightSlot(simSlot)
            }
        }
    }

    private fun openWidgetSettings() {
        startActivity(Intent(this, WidgetSettingsActivity::class.java))
    }

    private val phonePermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission(),
        ) { granted ->
            handlePhonePermissionResult(granted)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission(),
        ) { granted ->
            if (!granted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
                    viewModel.setNotificationPermissionPermanentlyDenied(permanentlyDenied)
                }
            }
            permissionStateNotifier.notifyChanged()
        }

    private fun handlePhonePermissionResult(granted: Boolean) {
        if (granted) {
            workScheduler.scheduleInitialSetup()
            workScheduler.schedulePeriodicUsageCheck()
            viewModel.setPhonePermissionPermanentlyDenied(false)
            // Move to next step: Usage Access
            if (!permissionManager.hasUsageStatsPermission()) {
                viewModel.openUsageAccessDialog()
            } else {
                checkNotifications(autoRequest = true)
            }
        } else {
            val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_PHONE_STATE)
            viewModel.setPhonePermissionPermanentlyDenied(permanentlyDenied)
        }
        permissionStateNotifier.notifyChanged()
    }

    private fun checkPermissionsAndInit() {
        if (permissionManager.hasPhoneStatePermission()) {
            viewModel.setPhonePermissionPermanentlyDenied(false)
            workScheduler.scheduleInitialSetup()
            workScheduler.schedulePeriodicUsageCheck()

            if (!permissionManager.hasUsageStatsPermission()) {
                viewModel.openUsageAccessDialog()
            } else {
                checkNotifications(autoRequest = true)
            }
        } else {
            // Check if we already requested it and it was denied permanently
            // This is tricky without a flag.
            // Most apps show a rationale banner first.
        }
    }

    private fun checkNotifications(autoRequest: Boolean) {
        if (!permissionManager.hasNotificationPermission()) {
            val settings = viewModel.appSettings.value
            if (autoRequest && !settings.notificationPermissionRequested) {
                requestNotificationPermission()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Determine if it was permanently denied based on rationale
                val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
                viewModel.setNotificationPermissionPermanentlyDenied(permanentlyDenied)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            viewModel.setNotificationPermissionRequested()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAppSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
        startActivity(intent)
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
            appWidgetManager.requestPinAppWidget(myProvider, null, null)
        } else {
            Toast.makeText(this, getString(R.string.widget_pin_unsupported), Toast.LENGTH_SHORT).show()
        }
    }

    private var lastRefreshAt = 0L
    private var lastResumeNotifyTime = 0L

    override fun onResume() {
        super.onResume()

        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshAt > 20_000L) {
            lastRefreshAt = now
            viewModel.refreshSimCards()
            viewModel.notifyDataChanged()
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastResumeNotifyTime > 1000) {
            permissionStateNotifier.notifyChanged()
            lastResumeNotifyTime = currentTime
        }

        // If we returned from settings, check if we can move to notifications
        if (permissionManager.hasPhoneStatePermission()) {
            viewModel.setPhonePermissionPermanentlyDenied(false)
            if (permissionManager.hasUsageStatsPermission()) {
                checkNotifications(autoRequest = false)
            }
        }
    }
}


