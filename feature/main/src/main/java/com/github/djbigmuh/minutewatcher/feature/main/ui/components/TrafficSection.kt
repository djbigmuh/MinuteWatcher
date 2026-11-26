package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.Icons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.github.djbigmuh.minutewatcher.core.common.util.FormatUtils
import com.github.djbigmuh.minutewatcher.core.common.util.NumberInputNormalizer
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSize
import com.github.djbigmuh.minutewatcher.core.designsystem.theme.MwSpacing
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.model.SimCardUiModel
import com.github.djbigmuh.minutewatcher.core.resources.R
import java.util.Locale

@Composable
fun TrafficSection(
    model: SimCardUiModel,
    statusColor: Color,
    onAdjustTraffic: () -> Unit,
    onShowTrafficInfo: () -> Unit,
) {
    if (!model.isTrafficLimitSet && model.trafficUsedMb <= 0) return
    val context = LocalContext.current

    HorizontalDivider(
        modifier = Modifier.padding(vertical = MwSpacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        TrafficHeader(onShowTrafficInfo)
        Spacer(modifier = Modifier.height(MwSpacing.xxs))
        TrafficUsageRow(context, model)
        if (model.isTrafficLimitSet && model.effectiveTrafficLimitGb > 0f) {
            Spacer(modifier = Modifier.height(MwSpacing.sm))
            LinearProgressIndicator(
                progress = {
                    ((model.trafficUsedMb / Constants.MB_PER_GB) / model.effectiveTrafficLimitGb).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().height(MwSize.usageProgressIndicatorHeight),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(MwSpacing.md))
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
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(MwSpacing.xs))
        IconButton(onClick = onShowTrafficInfo, modifier = Modifier.size(MwSize.trafficInfoButtonSize)) {
            Icon(
                Icons.Default.Info,
                contentDescription = stringResource(R.string.traffic_info_content_desc),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(MwSize.trafficInfoIconSize),
            )
        }
    }
}

@Composable
private fun TrafficUsageRow(
    context: Context,
    model: SimCardUiModel,
) {
    val locale = FormatUtils.getLocale(context)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (model.isTrafficLimitSet) {
            Text(
                text =
                    stringResource(
                        R.string.label_value_format,
                        stringResource(R.string.limit_text),
                        stringResource(
                            R.string.value_unit_format,
                            FormatUtils.formatTrafficGb(locale, model.effectiveTrafficLimitGb),
                            stringResource(R.string.gb_abbr),
                        ),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrafficRemainingRow(
    context: Context,
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
        Spacer(modifier = Modifier.width(MwSpacing.sm))

        if (model.trafficRemainingGb == null) {
            Text(text = stringResource(id = R.string.value_placeholder), style = MaterialTheme.typography.bodyLarge)
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
    val locale = FormatUtils.getLocale(LocalContext.current)
    if (shouldShowTrafficAdjustmentLabel(model.trafficAdjustmentGb, locale)) {
        AdjustmentLabel(
            value = FormatUtils.formatTrafficGb(locale, model.trafficAdjustmentGb),
            unit = stringResource(R.string.gb_abbr),
            isPositive = model.trafficAdjustmentGb > 0,
        )
    }
}

fun shouldShowTrafficAdjustmentLabel(
    adjustmentGb: Float,
    locale: Locale,
): Boolean =
    adjustmentGb != 0f &&
        !NumberInputNormalizer.isZeroValue(FormatUtils.formatTrafficGb(locale, adjustmentGb))
