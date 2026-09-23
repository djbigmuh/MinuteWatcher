package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.util.ColorProvider
import com.example.minutewatcher.core.model.util.UsageStatus
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.feature.main.ui.theme.usageColor

@Composable
private fun getStatusColor(status: UsageStatus): Color = MaterialTheme.colorScheme.usageColor(status)

/**
 * Renders SIM card item.
 * Contains icon, operator name, minutes and traffic state,
 * limit setting and reset buttons.
 *
 * @param model SIM card model.
 * @param onSetLimits Callback for setting limits.
 * @param onResetCounters Callback for resetting counters.
 * @param onToggleAutoReset Callback for toggling auto-reset.
 * @param onEditAutoResetDay Callback for editing auto-reset day.
 * @param onAdjustMinutes Callback for adjusting minutes.
 * @param onAdjustTraffic Callback for adjusting traffic.
 * @param onShowTrafficInfo Callback for showing traffic info.
 */
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
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
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
        MinutesSection(
            model = model,
            statusColor =
                getStatusColor(
                    ColorProvider.getUsageStatus(
                        model.minutesRemaining.toFloat(),
                        model.effectiveMinutesLimit.toFloat(),
                    ),
                ),
            onAdjustMinutes = onAdjustMinutes,
        )
        TrafficSection(
            model = model,
            statusColor =
                getStatusColor(
                    ColorProvider.getUsageStatus(model.trafficRemainingGb, model.effectiveTrafficLimitGb),
                ),
            onAdjustTraffic = onAdjustTraffic,
            onShowTrafficInfo = onShowTrafficInfo,
        )
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

/**
 * Renders SIM card item header.
 * Contains operator icon, operator name, and set limits button.
 *
 * @param model SIM card model.
 * @param onSetLimits Callback for setting limits.
 */
@Composable
private fun SimCardHeader(
    model: SimCardUiModel,
    onSetLimits: () -> Unit,
) {
    val simCardDetailsDesc = stringResource(id = R.string.sim_card_details_content_desc, model.operatorName)
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
            modifier = Modifier.weight(1f).semantics { contentDescription = simCardDetailsDesc },
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

/**
 * Renders auto-reset section for SIM card.
 * Contains auto-reset toggle switch and editable auto-reset day.
 *
 * @param model SIM card model.
 * @param onToggleAutoReset Callback for toggling auto-reset.
 * @param onEditAutoResetDay Callback for editing auto-reset day.
 */
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
            text =
                stringResource(
                    id = R.string.value_unit_format,
                    "",
                    stringResource(id = R.string.auto_reset_day_suffix),
                ).trim(),
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
