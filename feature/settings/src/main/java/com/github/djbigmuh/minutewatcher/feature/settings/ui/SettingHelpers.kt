package com.github.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppCheckbox
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSpacing

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
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = MwSpacing.sm, bottom = MwSpacing.xs),
    )
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = { onCheckedChange(!checked) })
                .padding(vertical = MwSpacing.xs),
    ) {
        AppCheckbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = enabledContentColor(enabled),
        )
    }
}
