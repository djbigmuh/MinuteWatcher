package com.example.minutewatcher.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Text используется для заголовков и меток настроек.
// style = MaterialTheme.typography.titleSmall для заголовков секций.
// style = MaterialTheme.typography.bodyMedium для меток.
// color = enabledContentColor(enabled) обеспечивает корректный цвет
// в зависимости от состояния enabled.

/**
 * Возвращает цвет контента в зависимости от состояния enabled.
 * При enabled = true возвращает MaterialTheme.colorScheme.onSurface.
 * При enabled = false возвращает MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f).
 * Это обеспечивает визуальное отображение disabled-состояния.
 */
@Composable
fun enabledContentColor(enabled: Boolean): Color =
    if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

// SettingLabel — переиспользуемый компонент для меток секций.
// style = MaterialTheme.typography.bodyMedium обеспечивает стиль метки.
// modifier = Modifier.padding(top = 8.dp) обеспечивает верхний отступ.
@Composable
fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

// CheckboxSetting — переиспользуемый компонент для чекбоксов.
// Содержит Checkbox и Text.
// onCheckedChange вызывается при изменении состояния чекбокса.
// enabled = enabled обеспечивает корректное состояние в зависимости
// от общего состояния enabled.
//
// Row clickable обеспечивает кликабельность всей строки.
// padding(vertical = 4.dp) обеспечивает вертикальные отступы.
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
                .padding(vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = enabledContentColor(enabled),
        )
    }
}
