package com.github.djbigmuh.minutewatcher.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSpacing

@Composable
fun AppSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        if (description != null) {
            Spacer(Modifier.height(MwSpacing.xs))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (content != null) {
            Spacer(Modifier.height(MwSpacing.md))
            content()
        }
    }
}
