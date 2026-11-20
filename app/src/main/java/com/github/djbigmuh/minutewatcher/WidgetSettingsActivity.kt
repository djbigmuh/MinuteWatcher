package com.github.djbigmuh.minutewatcher

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.djbigmuh.minutewatcher.feature.main.ui.theme.MinuteWatcherTheme
import com.github.djbigmuh.minutewatcher.feature.main.ui.theme.rememberIsDarkTheme
import com.github.djbigmuh.minutewatcher.feature.settings.ui.WidgetSettingsScreen
import com.github.djbigmuh.minutewatcher.feature.settings.ui.WidgetSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WidgetSettingsActivity : AppCompatActivity() {
    private val viewModel: WidgetSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
            val isDarkTheme = rememberIsDarkTheme(appTheme)

            MinuteWatcherTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WidgetSettingsScreen(
                        viewModel = viewModel,
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}
