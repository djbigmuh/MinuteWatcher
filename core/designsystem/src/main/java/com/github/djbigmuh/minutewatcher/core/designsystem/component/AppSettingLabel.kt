package com.github.djbigmuh.minutewatcher.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSpacing

@Composable
fun AppSettingLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = MwSpacing.sm, bottom = MwSpacing.xs),
    )
}
