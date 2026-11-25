package com.github.djbigmuh.minutewatcher.feature.main

import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppTopBar
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import com.github.djbigmuh.minutewatcher.core.resources.R
import com.github.djbigmuh.minutewatcher.feature.main.ui.components.LanguageMenu
import com.github.djbigmuh.minutewatcher.feature.main.ui.components.ThemeMenu

@Composable
fun SimCardTopBar(
    currentTheme: AppTheme,
    currentLanguage: String?,
    onThemeChange: (AppTheme) -> Unit,
    onLanguageChange: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onShowAbout: () -> Unit,
) {
    AppTopBar(
        title = stringResource(id = R.string.app_name),
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
