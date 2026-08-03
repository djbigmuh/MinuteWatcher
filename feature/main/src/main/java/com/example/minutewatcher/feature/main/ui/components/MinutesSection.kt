package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.minutewatcher.core.common.util.FormatUtils
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.util.ColorProvider
import com.example.minutewatcher.core.resources.R

@Composable
fun MinutesSection(
    model: SimCardUiModel,
    statusColor: Color,
    onAdjustMinutes: () -> Unit,
) {
    val status = ColorProvider.getUsageStatus(model.minutesRemaining.toFloat(), model.effectiveMinutesLimit.toFloat())
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${stringResource(
                    R.string.used_text,
                )}: ${FormatUtils.formatMinutes(model.minutesUsed)} ${stringResource(R.string.minutes_abbr)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (model.isMinutesLimitSet) {
                Text(
                    text = "${stringResource(
                        R.string.limit_text,
                    )}: ${FormatUtils.formatMinutes(model.effectiveMinutesLimit)} ${stringResource(R.string.minutes_abbr)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(id = R.string.remaining_text),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(8.dp))
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
            AdjustmentLabel(value = model.manualAdjustment.toString(), unit = stringResource(R.string.minutes_abbr))
        }
    }
}
