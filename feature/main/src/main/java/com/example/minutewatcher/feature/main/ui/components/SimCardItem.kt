package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.minutewatcher.core.common.util.FormatUtils
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.util.ColorProvider
import com.example.minutewatcher.core.model.util.UsageStatus
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.ui.theme.usageColor

@Composable
private fun getStatusColor(status: UsageStatus): Color = MaterialTheme.colorScheme.usageColor(status)

@Composable
fun SimCardItem(
    model: SimCardUiModel,
    onSetLimits: () -> Unit = {},
    onResetCounters: () -> Unit = {},
    onToggleAutoReset: (Boolean) -> Unit = {},
    onEditAutoResetDay: () -> Unit = {},
    onAdjustMinutes: () -> Unit = {},
    onAdjustTraffic: () -> Unit = {},
    onShowTrafficInfo: () -> Unit = {},
) {
    val simCardDetailsDesc = stringResource(id = R.string.sim_card_details_content_desc, model.operatorName)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = simCardDetailsDesc
                },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        SimCardContent(
            model = model,
            onSetLimits = onSetLimits,
            onResetCounters = onResetCounters,
            onToggleAutoReset = onToggleAutoReset,
            onEditAutoResetDay = onEditAutoResetDay,
            onAdjustMinutes = onAdjustMinutes,
            onAdjustTraffic = onAdjustTraffic,
            onShowTrafficInfo = onShowTrafficInfo,
        )
    }
}

@Composable
private fun SimCardContent(
    model: SimCardUiModel,
    onSetLimits: () -> Unit,
    onResetCounters: () -> Unit,
    onToggleAutoReset: (Boolean) -> Unit,
    onEditAutoResetDay: () -> Unit,
    onAdjustMinutes: () -> Unit,
    onAdjustTraffic: () -> Unit,
    onShowTrafficInfo: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        SimCardHeader(model = model, onSetLimits = onSetLimits)
        Spacer(modifier = Modifier.height(16.dp))
        MinutesSection(model = model, onAdjustMinutes = onAdjustMinutes)
        TrafficSection(model = model, onAdjustTraffic = onAdjustTraffic, onShowTrafficInfo = onShowTrafficInfo)
        TextButton(onClick = onResetCounters, modifier = Modifier.padding(top = 4.dp)) {
            Text(text = stringResource(id = R.string.reset_counters))
        }
        AutoResetSection(
            model = model,
            onToggleAutoReset = onToggleAutoReset,
            onEditAutoResetDay = onEditAutoResetDay,
        )
    }
}

@Composable
private fun SimCardHeader(
    model: SimCardUiModel,
    onSetLimits: () -> Unit,
) {
    val setLimitsDesc = stringResource(id = R.string.set_limits_content_desc, model.operatorName)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Image(
            painter = painterResource(id = model.iconRes),
            contentDescription = stringResource(id = R.string.sim_icon_content_desc),
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = model.operatorName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onSetLimits,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier =
                Modifier.height(32.dp).semantics {
                    contentDescription = setLimitsDesc
                },
        ) {
            Text(text = stringResource(id = R.string.limit_button), fontSize = 12.sp)
        }
    }
}

@Composable
private fun MinutesSection(
    model: SimCardUiModel,
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
                color = getStatusColor(status),
                onClick = onAdjustMinutes,
                contentDescription = stringResource(id = R.string.adjust_minutes_content_desc),
            )
        }
        if (model.manualAdjustment != 0) {
            AdjustmentLabel(value = model.manualAdjustment.toString(), unit = stringResource(R.string.minutes_abbr))
        }
    }
}

@Composable
private fun TrafficSection(
    model: SimCardUiModel,
    onAdjustTraffic: () -> Unit,
    onShowTrafficInfo: () -> Unit,
) {
    if (!model.isTrafficLimitSet && model.trafficUsedMb <= 0) return
    val status = ColorProvider.getUsageStatus(model.trafficRemainingGb, model.effectiveTrafficLimitGb)
    val context = LocalContext.current
    val trafficInfoDesc = stringResource(R.string.traffic_info_content_desc)

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
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
                    contentDescription = trafficInfoDesc,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${stringResource(R.string.used_text)}: ${FormatUtils.formatMbAsGb(context, model.trafficUsedMb)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (model.isTrafficLimitSet) {
                Text(
                    text = "${stringResource(
                        R.string.limit_text,
                    )}: ${FormatUtils.formatTrafficGb(model.effectiveTrafficLimitGb)}${stringResource(R.string.gb_abbr)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(id = R.string.traffic_remaining_text),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            EditableValueBox(
                value =
                    FormatUtils.formatTrafficDisplay(
                        context,
                        model.trafficRemainingGb,
                        model.trafficLimitMb / Constants.MB_PER_GB,
                        false,
                    ),
                color = getStatusColor(status),
                onClick = onAdjustTraffic,
                contentDescription = stringResource(id = R.string.adjust_traffic_content_desc),
            )
        }
        if (model.trafficAdjustmentGb != 0f) {
            AdjustmentLabel(
                value = FormatUtils.formatTrafficGb(model.trafficAdjustmentGb),
                unit = stringResource(R.string.gb_abbr),
                isPositive =
                    model.trafficAdjustmentGb > 0,
            )
        }
    }
}

@Composable
private fun AdjustmentLabel(
    value: String,
    unit: String,
    isPositive: Boolean = true,
) {
    Text(
        text = "${stringResource(R.string.adjustment_mark)}: ${if (isPositive) "+" else ""}$value$unit",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

@Composable
private fun AutoResetSection(
    model: SimCardUiModel,
    onToggleAutoReset: (Boolean) -> Unit,
    onEditAutoResetDay: () -> Unit,
) {
    val toggleAutoResetDesc = stringResource(id = R.string.toggle_auto_reset_content_desc)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(text = stringResource(id = R.string.reset_label), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.width(8.dp))
        EditableValueBox(
            value = model.autoResetDay.toString(),
            color = MaterialTheme.colorScheme.onSurface,
            onClick = onEditAutoResetDay,
            horizontalPadding = 8,
            contentDescription = stringResource(id = R.string.change_auto_reset_day_content_desc),
        )
        Text(
            text = " " + stringResource(id = R.string.auto_reset_day_suffix),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = model.autoResetEnabled,
            onCheckedChange = onToggleAutoReset,
            modifier =
                Modifier.minimumInteractiveComponentSize().scale(Constants.UI.SWITCH_SCALE).semantics {
                    contentDescription = toggleAutoResetDesc
                },
        )
    }
}

@Composable
fun EditableValueBox(
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
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .drawBehind {
                    val strokeWidth = 2.dp.toPx()
                    val y = size.height - strokeWidth / 2
                    drawLine(
                        color = underlineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth,
                    )
                }.semantics {
                    role = Role.Button
                    contentDescription?.let { this.contentDescription = it }
                }.clickable { onClick() }
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
