package com.example.minutewatcher

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.ui.theme.MinuteWatcherTheme
import com.example.minutewatcher.feature.settings.ui.WidgetSettingsScreen
import com.example.minutewatcher.feature.settings.ui.WidgetSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WidgetSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: WidgetSettingsViewModel = hiltViewModel()
            MinuteWatcherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WidgetSettingsScreen(
                        viewModel = viewModel,
                        onSave = {
                            viewModel.saveAndNotify { success ->
                                if (success) {
                                    Toast
                                        .makeText(
                                            this,
                                            getString(R.string.settings_saved_toast),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    finish()
                                } else {
                                    Toast
                                        .makeText(
                                            this,
                                            getString(R.string.error),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                        },
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}
