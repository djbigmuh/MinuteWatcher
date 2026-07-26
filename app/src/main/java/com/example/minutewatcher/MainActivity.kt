package com.example.minutewatcher

import android.Manifest
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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.minutewatcher.core.data.UsageCheckWorker
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.R
import com.example.minutewatcher.core.model.util.PermissionManager
import com.example.minutewatcher.feature.main.MainViewModel
import com.example.minutewatcher.feature.main.SimCardScreen
import com.example.minutewatcher.feature.main.ui.theme.MinuteWatcherTheme
import com.example.minutewatcher.feature.widget.MinutesGlanceWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var permissionManager: PermissionManager

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
                            lifecycleScope.launch { openWidgetSettings() }
                        },
                        onAddWidget = { addWidgetToHomeScreen() },
                        onToggleTheme = {
                            val currentTheme = viewModel.appTheme.value
                            val isCurrentlyDark =
                                if (currentTheme == AppTheme.FOLLOW_SYSTEM) {
                                    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                                } else {
                                    currentTheme == AppTheme.DARK
                                }
                            viewModel.setTheme(if (isCurrentlyDark) AppTheme.LIGHT else AppTheme.DARK)
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

    private suspend fun openWidgetSettings() {
        try {
            val intent = Intent(this, WidgetSettingsActivity::class.java)
            for (i in 0..1) {
                intent.putExtra(Constants.Keys.SIM_OPERATOR + i, viewModel.getSimOperatorAsync(i))
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Error logged elsewhere
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                scheduleUsageCheck()
            }
        }

    private fun checkPermissionsAndInit() {
        if (permissionManager.hasPhoneStatePermission()) {
            scheduleUsageCheck()

            // Request usage stats permission if not granted
            if (!permissionManager.hasUsageStatsPermission()) {
                Toast.makeText(this, getString(R.string.usage_access_rationale), Toast.LENGTH_LONG).show()
                val intent =
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                // Some devices might not support direct package uri for usage settings
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }

            // Optional: Request notifications if not granted
            if (!permissionManager.hasNotificationPermission()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        } else {
            // Request critical permission
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
        }
    }

    private fun scheduleUsageCheck() {
        val workRequest =
            PeriodicWorkRequestBuilder<UsageCheckWorker>(1, TimeUnit.HOURS)
                .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "UsageCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
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
            Toast.makeText(this, getString(R.string.dynamic_preview), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSimCards()
        viewModel.notifyDataChanged()
    }
}
