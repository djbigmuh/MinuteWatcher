package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.resources.R

/**
 * Рендерит секцию трафика SIM-карт.
 * Содержит использованный трафик, лимит трафика и остаток трафика.
 * Остаток трафика редактируемый (EditableValueBox).
 *
 * @param model Модель SIM-карты.
 * @param statusColor Цвет статуса (нормальный/низкий/критический).
 * @param onAdjustTraffic Колбэк для корректировки трафика.
 * @param onShowTrafficInfo Колбэк для показа информации о трафике.
 */
@Composable
fun TrafficSection(
    model: SimCardUiModel,
    statusColor: Color,
    onAdjustTraffic: () -> Unit,
    onShowTrafficInfo: () -> Unit,
) {
    if (!model.isTrafficLimitSet && model.trafficUsedMb <= 0) return
    val context = LocalContext.current

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        TrafficHeader(onShowTrafficInfo)
        TrafficUsageRow(context, model)
        Spacer(modifier = Modifier.height(8.dp))
        TrafficRemainingRow(context, model, statusColor, onAdjustTraffic)
        TrafficAdjustment(model)
    }
}

@Composable
private fun TrafficHeader(onShowTrafficInfo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(id = R.string.traffic_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onShowTrafficInfo, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Default.Info,
                contentDescription = stringResource(R.string.traffic_info_content_desc),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun TrafficUsageRow(
    context: android.content.Context,
    model: SimCardUiModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                stringResource(
                    R.string.label_value_format,
                    stringResource(R.string.used_text),
                    FormatUtils.formatMbAsGb(context, model.trafficUsedMb),
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (model.isTrafficLimitSet) {
            Text(
                text =
                    stringResource(
                        R.string.label_value_format,
                        stringResource(R.string.limit_text),
                        stringResource(
                            R.string.value_unit_format,
                            FormatUtils.formatTrafficGb(model.effectiveTrafficLimitGb),
                            stringResource(R.string.gb_abbr),
                        ),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TrafficRemainingRow(
    context: android.content.Context,
    model: SimCardUiModel,
    statusColor: Color,
    onAdjustTraffic: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(id = R.string.traffic_remaining_text),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(8.dp))

        if (model.trafficRemainingGb < 0) {
            Text(text = "-", style = MaterialTheme.typography.bodyLarge)
        } else {
            EditableValueBox(
                value =
                    FormatUtils.formatTrafficDisplay(
                        context,
                        model.trafficRemainingGb,
                        model.trafficLimitMb / Constants.MB_PER_GB,
                        false,
                    ),
                color = statusColor,
                onClick = onAdjustTraffic,
                contentDescription = stringResource(id = R.string.adjust_traffic_content_desc),
            )
        }
    }
}

@Composable
private fun TrafficAdjustment(model: SimCardUiModel) {
    if (model.trafficAdjustmentGb != 0f) {
        AdjustmentLabel(
            value = FormatUtils.formatTrafficGb(model.trafficAdjustmentGb),
            unit = stringResource(R.string.gb_abbr),
            isPositive = model.trafficAdjustmentGb > 0,
        )
    }
}
