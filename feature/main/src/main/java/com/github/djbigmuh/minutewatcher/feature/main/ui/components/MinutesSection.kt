package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.common.util.FormatUtils
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSpacing
import com.github.djbigmuh.minutewatcher.core.model.SimCardUiModel
import com.github.djbigmuh.minutewatcher.core.resources.R

@Composable
fun MinutesSection(
    model: SimCardUiModel,
    statusColor: Color,
    onAdjustMinutes: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MinutesUsageAndLimitRow(model = model)
        if (model.isMinutesLimitSet && model.effectiveMinutesLimit > 0) {
            Spacer(modifier = Modifier.height(MwSpacing.sm))
            LinearProgressIndicator(
                progress = { (model.minutesUsed.toFloat() / model.effectiveMinutesLimit).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(MwSpacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(id = R.string.remaining_text),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(MwSpacing.sm))
            EditableValueBox(
                value =
                    FormatUtils.formatMinutesDisplay(
                        LocalContext.current,
                        model.minutesRemaining,
                        model.minutesLimit,
                        model.manualAdjustment,
                        false,
                    ),
                color = statusColor,
                onClick = onAdjustMinutes,
                contentDescription = stringResource(id = R.string.adjust_minutes_content_desc),
            )
        }
        if (model.manualAdjustment != 0) {
            AdjustmentLabel(
                value = model.manualAdjustment.toString(),
                unit = stringResource(R.string.minutes_abbr),
                isPositive = model.manualAdjustment > 0,
            )
        }
    }
}

@Composable
private fun MinutesUsageAndLimitRow(model: SimCardUiModel) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text =
                stringResource(
                    R.string.label_value_format,
                    stringResource(R.string.used_text),
                    stringResource(
                        R.string.value_unit_format,
                        model.minutesUsed.toString(),
                        stringResource(R.string.minutes_abbr),
                    ),
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (model.isMinutesLimitSet) {
            Text(
                text =
                    stringResource(
                        R.string.label_value_format,
                        stringResource(R.string.limit_text),
                        stringResource(
                            R.string.value_unit_format,
                            model.effectiveMinutesLimit.toString(),
                            stringResource(R.string.minutes_abbr),
                        ),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
