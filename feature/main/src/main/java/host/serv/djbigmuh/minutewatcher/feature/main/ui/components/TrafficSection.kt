package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import android.content.Context
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
import host.serv.djbigmuh.minutewatcher.core.common.util.FormatUtils
import host.serv.djbigmuh.minutewatcher.core.common.util.NumberInputNormalizer
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.SimCardUiModel
import host.serv.djbigmuh.minutewatcher.core.resources.R
import java.util.Locale

/**
 * Renders traffic section for SIM card.
 * Contains traffic used, traffic limit, and traffic remaining.
 * Traffic remaining is editable (EditableValueBox).
 *
 * @param model SIM card model.
 * @param statusColor Status color (normal/low/critical).
 * @param onAdjustTraffic Callback for adjusting traffic.
 * @param onShowTrafficInfo Callback for showing traffic info.
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
        Spacer(modifier = Modifier.width(8.dp))

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
