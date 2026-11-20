package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import com.github.djbigmuh.minutewatcher.core.model.AppTheme
import com.github.djbigmuh.minutewatcher.core.resources.R
import com.github.djbigmuh.minutewatcher.feature.main.supportedLanguageOptions

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
                DropdownMenuItem(
                    text = { Text(stringResource(option.nameRes)) },
                    onClick = {
                        onLanguageChange(option.code)
                        expanded = false
                    },
                    trailingIcon = {
                        if (currentLanguage == option.code) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.check_mark),
                            )
                        }
                    },
                )
            }
        }
    }
}

private data class ThemeOption(
    val theme: AppTheme,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
)

@Composable
fun ThemeMenu(
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val themes =
        listOf(
            ThemeOption(AppTheme.FOLLOW_SYSTEM, R.string.theme_system, R.drawable.ic_theme_system),
            ThemeOption(AppTheme.LIGHT, R.string.theme_light, R.drawable.ic_theme_light),
            ThemeOption(AppTheme.DARK, R.string.theme_dark, R.drawable.ic_theme_dark),
        )

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_palette),
                contentDescription = stringResource(R.string.menu_theme_content_desc),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            themes.forEach { item ->
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            painter = painterResource(item.iconRes),
                            contentDescription = null,
                        )
                    },
                    text = { Text(stringResource(item.labelRes)) },
                    onClick = {
                        onThemeChange(item.theme)
                        expanded = false
                    },
                    trailingIcon = {
                        if (currentTheme == item.theme) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.check_mark),
                            )
                        }
                    },
                )
            }
        }
    }
}
