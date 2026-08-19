package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.ui.res.stringResource
import com.example.minutewatcher.core.model.AppTheme
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.LanguageOption

// LanguageMenu — выпадающее меню для выбора языка.
// Содержит варианты: Системный, Английский, Русский, и т. д.
// onLanguageChange вызывается при выборе языка.
//
// DropdownMenu показывается при нажатии на иконку.
// При выборе языка меню закрывается.
@Composable
fun LanguageMenu(
    currentLanguage: String?,
    onLanguageChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val languages =
        listOf(
            LanguageOption(null, stringResource(R.string.language_system), "🌐"),
            LanguageOption("en", stringResource(R.string.language_en), "🇺🇸"),
            LanguageOption("ru", stringResource(R.string.language_ru), "🇷🇺"),
            LanguageOption("de", stringResource(R.string.language_de), "🇩🇪"),
            LanguageOption("fr", stringResource(R.string.language_fr), "🇫🇷"),
            LanguageOption("es", stringResource(R.string.language_es), "🇪🇸"),
            LanguageOption("ar", stringResource(R.string.language_ar), "🇸🇦"),
            LanguageOption("hi", stringResource(R.string.language_hi), "🇮🇳"),
            LanguageOption("ja", stringResource(R.string.language_ja), "🇯🇵"),
            LanguageOption("zh", stringResource(R.string.language_zh), "🇨🇳"),
        )

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Language, contentDescription = "Change Language")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.flag} ${option.name}") },
                    onClick = {
                        onLanguageChange(option.code)
                        expanded = false
                    },
                    trailingIcon = {
                        if (currentLanguage == option.code) {
                            Text("✓")
                        }
                    },
                )
            }
        }
    }
}

// ThemeMenu — выпадающее меню для выбора темы.
// Содержит варианты: Системная, Светлая, Тёмная.
// onThemeChange вызывается при выборе темы.
//
// DropdownMenu показывается при нажатии на иконку.
// При выборе темы меню закрывается.
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
            Icon(Icons.Default.Palette, contentDescription = "Change Theme")
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
                            Text("✓")
                        }
                    },
                )
            }
        }
    }
}
