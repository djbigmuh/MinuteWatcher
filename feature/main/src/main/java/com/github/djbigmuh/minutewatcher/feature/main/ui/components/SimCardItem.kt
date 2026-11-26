package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppCard
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppOutlinedButton
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppSwitch
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppTextButton
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MinuteWatcherTheme
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwShape
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSize
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSpacing
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.SimCardUiModel
import com.github.djbigmuh.minutewatcher.core.model.util.ColorProvider
import com.github.djbigmuh.minutewatcher.core.model.util.UsageStatus
import com.github.djbigmuh.minutewatcher.core.resources.R
import com.github.djbigmuh.minutewatcher.feature.main.ui.theme.usageColor

@Composable
private fun getStatusColor(status: UsageStatus): Color = MaterialTheme.colorScheme.usageColor(status)

/**
 * Renders SIM card item.
 * Contains icon, operator name, minutes and traffic state,
 * limit setting and reset buttons.
 */
@Composable
fun SimCardItem(
    model: SimCardUiModel,
    highlighted: Boolean = false,
    onSetLimits: () -> Unit = {},
    onResetCounters: () -> Unit = {},
    onToggleAutoReset: (Boolean) -> Unit = {},
    onEditAutoResetDay: () -> Unit = {},
    onAdjustMinutes: () -> Unit = {},
    onAdjustTraffic: () -> Unit = {},
    onShowTrafficInfo: () -> Unit = {},
) {
    AppCard(
        modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = MwSpacing.sm)
                    .then(
                        if (highlighted) Modifier.testTag("sim_card_highlighted") else Modifier,
                    ),
        contentPadding = PaddingValues(0.dp),
        border =
                BorderStroke(
                    width = if (highlighted) 2.dp else 1.dp,
                    color =
                        if (highlighted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                ),
        shape = RoundedCornerShape(MwShape.extraLarge),
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
    Column(modifier = Modifier.padding(MwSpacing.lg)) {
        SimCardHeader(model = model, onSetLimits = onSetLimits)
        Spacer(modifier = Modifier.height(MwSpacing.xl))
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
                    ColorProvider.getUsageStatus(model.trafficRemainingGb ?: 0f, model.effectiveTrafficLimitGb),
                ),
            onAdjustTraffic = onAdjustTraffic,
            onShowTrafficInfo = onShowTrafficInfo,
        )
        AppTextButton(
            text = stringResource(id = R.string.reset_counters),
            onClick = onResetCounters,
            modifier = Modifier.padding(top = MwSpacing.xs),
        )
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
    val simCardDetailsDesc = stringResource(id = R.string.sim_card_details_content_desc, model.operatorName)
    val setLimitsDesc = stringResource(id = R.string.set_limits_content_desc, model.operatorName)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Image(
            painter = painterResource(id = model.iconRes),
            contentDescription = stringResource(id = R.string.sim_icon_content_desc),
            modifier =
                Modifier
                    .size(MwSize.operatorIconSize)
                    .clip(RoundedCornerShape(MwShape.operatorIcon))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(7.dp),
        )
        Spacer(modifier = Modifier.width(MwSpacing.md))
        Text(
            text = model.operatorName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .semantics { contentDescription = simCardDetailsDesc },
        )
        AppOutlinedButton(
            text = stringResource(id = R.string.limit_button),
            onClick = onSetLimits,
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 5.dp),
            modifier =
                Modifier
                    .height(MwSize.limitButtonHeight)
                    .semantics { contentDescription = setLimitsDesc },
        )
    }
}

@Composable
private fun AutoResetSection(
    model: SimCardUiModel,
    onToggleAutoReset: (Boolean) -> Unit,
    onEditAutoResetDay: () -> Unit,
) {
    val toggleAutoResetDesc = stringResource(id = R.string.toggle_auto_reset_content_desc)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = MwSpacing.xs),
    ) {
        Text(text = stringResource(id = R.string.reset_label), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.width(MwSpacing.sm))
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(
            checked = model.autoResetEnabled,
            onCheckedChange = onToggleAutoReset,
            modifier =
                Modifier
                    .minimumInteractiveComponentSize()
                    .scale(Constants.UI.SWITCH_SCALE)
                    .semantics { contentDescription = toggleAutoResetDesc },
        )
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun SimCardItemPreview() {
    MinuteWatcherTheme {
        SimCardItem(
            model =
                SimCardUiModel(
                    slot = 0,
                    identity = "sim_0",
                    operatorName = "MTS",
                    iconRes = R.drawable.ic_unknown,
                    minutesUsed = 45,
                    minutesLimit = 100,
                    manualAdjustment = 0,
                    minutesRemaining = 55,
                    effectiveMinutesLimit = 100,
                    isMinutesLimitSet = true,
                    trafficUsedMb = 500f,
                    trafficLimitMb = 2048,
                    trafficRemainingGb = 1.5f,
                    effectiveTrafficLimitGb = 2.0f,
                    trafficAdjustmentGb = 0f,
                    isTrafficLimitSet = true,
                    autoResetEnabled = true,
                    autoResetDay = 1,
                    rolloverEnabled = false,
                ),
            highlighted = false,
            onSetLimits = {},
            onResetCounters = {},
            onToggleAutoReset = {},
            onEditAutoResetDay = {},
            onAdjustMinutes = {},
            onAdjustTraffic = {},
            onShowTrafficInfo = {},
        )
    }
}
