package host.serv.djbigmuh.minutewatcher.feature.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import host.serv.djbigmuh.minutewatcher.core.model.AppTheme
import host.serv.djbigmuh.minutewatcher.core.resources.R
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.LanguageMenu
import host.serv.djbigmuh.minutewatcher.feature.main.ui.components.ThemeMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimCardTopBar(
    currentTheme: AppTheme,
    currentLanguage: String?,
    onThemeChange: (AppTheme) -> Unit,
    onLanguageChange: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onShowAbout: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(id = R.string.app_name)) },
        actions = {
            ThemeMenu(currentTheme = currentTheme, onThemeChange = onThemeChange)
            LanguageMenu(currentLanguage = currentLanguage, onLanguageChange = onLanguageChange)
            IconButton(onClick = onShowAbout) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(id = R.string.about_content_desc),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(id = R.string.settings_widget_title))
            }
        },
    )
}
