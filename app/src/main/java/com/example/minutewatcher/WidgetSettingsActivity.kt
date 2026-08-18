package com.example.minutewatcher

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.minutewatcher.feature.main.ui.theme.MinuteWatcherTheme
import com.example.minutewatcher.feature.settings.ui.WidgetSettingsScreen
import com.example.minutewatcher.feature.settings.ui.WidgetSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Активность настроек виджета.
 *
 * ВАЖНО: Класс ОБЯЗАТЕЛЬНО должен быть помечен аннотацией [@AndroidEntryPoint],
 * чтобы Hilt мог внедрить [WidgetSettingsViewModel]. Отсутствие этой аннотации
 * приведет к крашу при вызове hiltViewModel() из-за ошибки конфигурации DI.
 */
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
                        onSave = { viewModel.saveAndNotify() },
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}
