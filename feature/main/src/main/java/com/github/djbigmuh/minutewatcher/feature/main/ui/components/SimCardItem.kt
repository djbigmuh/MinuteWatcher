package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppCard
import com.github.djbigmuh.minutewatcher.core.designsystem.component.AppSwitch
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwShape
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.SimCardUiModel
import com.github.djbigmuh.minutewatcher.core.model.util.ColorProvider
import com.github.djbigmuh.minutewatcher.core.model.util.UsageStatus
import com.github.djbigmuh.minutewatcher.core.resources.R
import com.github.djbigmuh.minutewatcher.feature.main.ui.theme.usageColor

@Composable
private fun getStatusColor(status: UsageStatus): Color = MaterialTheme.colorScheme.usageColor(status)

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
        modifier = Modifier
            .padding(vertical = 7.dp)
            .then(if (highlighted) Modifier.testTag("sim_card_highlighted") else Modifier),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        border = BorderStroke(
            width = if (highlighted) 2.dp else 1.dp,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
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
    Column {
        SimCardHeader(model = model, onSetLimits = onSetLimits)
        Spacer(modifier = Modifier.height(20.dp))
        MinutesSection(
            model = model,
            statusColor = getStatusColor(
                ColorProvider.getUsageStatus(model.minutesRemaining.toFloat(), model.effectiveMinutesLimit.toFloat()),
            ),
            onAdjustMinutes = onAdjustMinutes,
        )
        TrafficSection(
            model = model,
            statusColor = getStatusColor(
                ColorProvider.getUsageStatus(model.trafficRemainingGb ?: 0f, model.effectiveTrafficLimitGb),
            ),
            onAdjustTraffic = onAdjustTraffic,
            onShowTrafficInfo = onShowTrafficInfo,
        )
        TextButton(onClick = onResetCounters, modifier = Modifier.padding(top = 6.dp)) {
            Text(text = stringResource(id = R.string.reset_counters))
        }
        AutoResetSection(
            model = model,
            onToggleAutoReset = onToggleAutoReset,
            onEditAutoResetDay = onEditAutoResetDay,
        )
    }
}
