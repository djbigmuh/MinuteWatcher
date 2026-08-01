package com.example.minutewatcher

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
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
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.util.AppLogger
import com.example.minutewatcher.core.common.util.PermissionManager
import com.example.minutewatcher.core.data.WorkScheduler
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.MainViewModel
import com.example.minutewatcher.feature.main.SimCardScreen
import com.example.minutewatcher.feature.main.ui.theme.MinuteWatcherTheme
import com.example.minutewatcher.feature.widget.MinutesGlanceWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
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

    @Inject lateinit var permissionStateNotifier: PermissionStateNotifier

    @Inject lateinit var workScheduler: WorkScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        observeAppTheme()
        observeAppLanguage()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setLocusContext(LocusId("main_screen"), null)
        }

        setContent {
            AppContent()
        }

        permissionHandler.checkPermissionsAndInit()
        handleIntent(intent)
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
                SimCardScreen(
                    viewModel = viewModel,
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
            val simSlot = it.getIntExtra("sim_slot", -1)
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
                workScheduler.scheduleInitialSetup()
                workScheduler.schedulePeriodicUsageCheck()
                viewModel.setPhonePermissionPermanentlyDenied(false)
                if (!permissionManager.hasUsageStatsPermission()) {
                    viewModel.setUsageAccessDialog(true)
                } else {
                    checkNotifications(autoRequest = true)
                }
            } else {
                val permanentlyDenied =
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        this@MainActivity,
                        Manifest.permission.READ_PHONE_STATE,
                    )
                viewModel.setPhonePermissionPermanentlyDenied(permanentlyDenied)
            }
            permissionStateNotifier.notifyChanged()
        }

        private fun handleNotificationResult(granted: Boolean) {
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permanentlyDenied =
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                viewModel.setNotificationPermissionPermanentlyDenied(permanentlyDenied)
            }
            permissionStateNotifier.notifyChanged()
        }

        fun checkPermissionsAndInit() {
            lifecycleScope.launch {
                val settings = viewModel.appSettings.first()
                if (permissionManager.hasPhoneStatePermission()) {
                    viewModel.setPhonePermissionPermanentlyDenied(false)
                    workScheduler.scheduleInitialSetup()
                    workScheduler.schedulePeriodicUsageCheck()

                    if (!permissionManager.hasUsageStatsPermission()) {
                        viewModel.setUsageAccessDialog(true)
                    } else {
                        checkNotifications(autoRequest = true)
                    }
                } else if (settings.phonePermissionRequested) {
                    val permanentlyDenied =
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            this@MainActivity,
                            Manifest.permission.READ_PHONE_STATE,
                        )
                    viewModel.setPhonePermissionPermanentlyDenied(permanentlyDenied)
                }
            }
        }

        fun checkNotifications(autoRequest: Boolean) {
            lifecycleScope.launch {
                if (!permissionManager.hasNotificationPermission()) {
                    val settings = viewModel.appSettings.first()
                    if (autoRequest && !settings.notificationPermissionRequested) {
                        requestNotificationPermission()
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val permanentlyDenied =
                            !ActivityCompat.shouldShowRequestPermissionRationale(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        viewModel.setNotificationPermissionPermanentlyDenied(permanentlyDenied)
                    }
                }
            }
        }

        fun requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                viewModel.setNotificationPermissionRequested()
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openAppSettings() {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
        startActivity(intent)
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            AppLogger.e("Usage access settings not found, opening app settings", e)
            openAppSettings()
        }
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

    override fun onResume() {
        super.onResume()

        viewModel.onActivityResume(
            nowElapsed = SystemClock.elapsedRealtime(),
            currentTimeMillis = System.currentTimeMillis(),
            permissionStateNotifier = permissionStateNotifier,
        )

        if (permissionManager.hasPhoneStatePermission()) {
            viewModel.setPhonePermissionPermanentlyDenied(false)
            if (permissionManager.hasUsageStatsPermission()) {
                permissionHandler.checkNotifications(autoRequest = false)
            }
        }
    }
}
