package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.supportedLanguageOptions

// LanguageMenu — dropdown menu for selecting language.
// Contains options: System, English, Russian, etc.
// onLanguageChange is called when a language is selected.
//
// DropdownMenu is displayed on icon click.
// Menu closes on language selection.
@Composable
fun LanguageMenu(
    currentLanguage: String?,
    onLanguageChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_language),
                contentDescription = stringResource(R.string.menu_language_content_desc),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            supportedLanguageOptions.forEach { option ->
                val name = stringResource(option.nameRes)
                DropdownMenuItem(
                    text = { Text("${option.flag} $name") },
                    onClick = {
                        onLanguageChange(option.code)
                        expanded = false
                    },
                    trailingIcon = {
                        if (currentLanguage == option.code) {
                            Text(stringResource(R.string.check_mark))
                        }
                    },
                )
            }
        }
    }
}

// ThemeMenu — dropdown menu for selecting theme.
// Contains options: System, Light, Dark.
// onThemeChange is called when a theme is selected.
//
// DropdownMenu is displayed on icon click.
// Menu closes on theme selection.
@Composable
fun ThemeMenu(
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val themes =
        listOf(
            Triple(AppTheme.FOLLOW_SYSTEM, stringResource(R.string.theme_system), "🌓"),
            Triple(AppTheme.LIGHT, stringResource(R.string.theme_light), "☀️"),
            Triple(AppTheme.DARK, stringResource(R.string.theme_dark), "🌙"),
        )

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_palette),
                contentDescription = stringResource(R.string.menu_theme_content_desc),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            themes.forEach { (theme, name, icon) ->
                DropdownMenuItem(
                    text = { Text("$icon $name") },
                    onClick = {
                        onThemeChange(theme)
                        expanded = false
                    },
                    trailingIcon = {
                        if (currentTheme == theme) {
                            Text(stringResource(R.string.check_mark))
                        }
                    },
                )
            }
        }
    }
}
