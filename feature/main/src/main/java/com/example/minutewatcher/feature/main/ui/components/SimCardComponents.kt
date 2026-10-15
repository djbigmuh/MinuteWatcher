package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.resources.R

/**
 * Renders adjustment label (e.g., "+10 min." or "-0.5 GB").
 * Displayed when adjustment is non-zero.
 *
 * @param value Adjustment value.
 * @param unit Unit of measurement ("min." or "GB").
 * @param isPositive true if adjustment is positive.
 */
@Composable
internal fun AdjustmentLabel(
    value: String,
    unit: String,
    isPositive: Boolean,
) {
    Text(
        text =
            stringResource(
                R.string.adjustment_label_format,
                stringResource(R.string.adjustment_mark),
                if (isPositive) "+" else "",
                value,
                unit,
            ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

/**
 * Renders editable value box (remaining minutes or traffic).
 * Triggers onClick on click to open adjustment dialog.
 *
 * @param value Displayed value.
 * @param color Value color (status).
 * @param onClick Callback on click.
 * @param horizontalPadding Horizontal padding.
 * @param contentDescription Accessibility description.
 */
@Composable
internal fun EditableValueBox(
    value: String,
    color: Color,
    onClick: () -> Unit,
    horizontalPadding: Int = 12,
    contentDescription: String? = null,
) {
    val underlineColor = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp),
                )
                .drawBehind {
                    val strokeWidth = 2.dp.toPx()
                    val y = size.height - strokeWidth / 2
                    drawLine(
                        color = underlineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth,
                    )
                }
                .clip(RoundedCornerShape(4.dp))
                .semantics {
                    role = Role.Button
                    contentDescription?.let { this.contentDescription = it }
                }
                .clickable { onClick() }
                .padding(horizontal = horizontalPadding.dp, vertical = 4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}
