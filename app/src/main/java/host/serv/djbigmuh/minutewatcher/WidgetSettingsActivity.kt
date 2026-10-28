package host.serv.djbigmuh.minutewatcher

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import host.serv.djbigmuh.minutewatcher.feature.main.ui.theme.MinuteWatcherTheme
import host.serv.djbigmuh.minutewatcher.feature.main.ui.theme.rememberIsDarkTheme
import host.serv.djbigmuh.minutewatcher.feature.settings.ui.WidgetSettingsScreen
import host.serv.djbigmuh.minutewatcher.feature.settings.ui.WidgetSettingsViewModel

@AndroidEntryPoint
class WidgetSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: WidgetSettingsViewModel = hiltViewModel()
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
