package com.github.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppCheckboxRow
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppSettingLabel

/**
 * Returns the content color based on the enabled state.
 */
@Composable
fun enabledContentColor(enabled: Boolean): Color =
    if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

/**
 * Reusable component for section labels.
 */
@Composable
fun SettingLabel(text: String) {
    AppSettingLabel(text = text)
}

/**
 * Reusable component for checkbox settings.
 */
@Composable
fun CheckboxSetting(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    AppCheckboxRow(
        label = label,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
    )
}
