package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.resources.R
import com.example.minutewatcher.core.model.SimCardUiModel
import com.example.minutewatcher.core.model.util.ColorProvider
import com.example.minutewatcher.core.android.util.FormatUtils
import com.example.minutewatcher.core.model.util.UsageColors
import com.example.minutewatcher.core.model.util.UsageStatus

@Composable
fun getStatusColor(status: UsageStatus): Color = Color(UsageColors.get(status, isSystemInDarkTheme()))

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
    val context = LocalContext.current
    val simCardDetailsDesc = stringResource(id = R.string.sim_card_details_content_desc, model.operatorName)
    val toggleAutoResetDesc = stringResource(id = R.string.toggle_auto_reset_content_desc)
    val setLimitsDesc = stringResource(id = R.string.set_limits_content_desc, model.operatorName)
    val trafficInfoDesc = stringResource(id = R.string.traffic_info_content_desc)

    val minutesStatus =
        ColorProvider.getUsageStatus(
            model.minutesRemaining.toFloat(),
            model.effectiveMinutesLimit.toFloat(),
        )
    val trafficStatus = ColorProvider.getUsageStatus(model.trafficRemainingGb, model.effectiveTrafficLimitGb)

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
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Image(
                    painter = painterResource(id = model.iconRes),
                    contentDescription = stringResource(id = R.string.sim_icon_content_desc),
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.operatorName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
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

            Spacer(modifier = Modifier.height(16.dp))

            // Minutes Section
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${stringResource(
                            id = R.string.used_text,
                        )}: ${FormatUtils.formatMinutes(model.minutesUsed)} ${stringResource(id = R.string.minutes_abbr)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (model.isMinutesLimitSet) {
                        Text(
                            text = "${stringResource(
                                id = R.string.limit_text,
                            )}: ${FormatUtils.formatMinutes(model.effectiveMinutesLimit)} ${stringResource(id = R.string.minutes_abbr)}",
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
                                context,
                                model.minutesRemaining,
                                model.minutesLimit,
                                model.manualAdjustment,
                                showLimit = false,
                            ),
                        color = getStatusColor(minutesStatus),
                        onClick = onAdjustMinutes,
                        contentDescription = stringResource(id = R.string.adjust_minutes_content_desc),
                    )
                }

                if (model.manualAdjustment != 0) {
                    Text(
                        text = "${stringResource(
                            id = R.string.adjustment_mark,
                        )}: ${if (model.manualAdjustment > 0) "+" else ""}${model.manualAdjustment}${stringResource(
                            id = R.string.minutes_abbr,
                        )}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
            }

            // Traffic Section
            if (model.isTrafficLimitSet || (model.trafficUsedMb > 0)) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(id = R.string.traffic_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onShowTrafficInfo,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
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
                            text = "${stringResource(
                                id = R.string.used_text,
                            )}: ${FormatUtils.formatTrafficMb(context, model.trafficUsedMb)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (model.isTrafficLimitSet) {
                            Text(
                                text = "${stringResource(
                                    id = R.string.limit_text,
                                )}: ${FormatUtils.formatTrafficGb(model.effectiveTrafficLimitGb)}${stringResource(id = R.string.gb_abbr)}",
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
                                    showLimit = false,
                                ),
                            color = getStatusColor(trafficStatus),
                            onClick = onAdjustTraffic,
                            contentDescription = stringResource(id = R.string.adjust_traffic_content_desc),
                        )
                    }

                    if (model.trafficAdjustmentGb != 0f) {
                        Text(
                            text = "${stringResource(
                                id = R.string.adjustment_mark,
                            )}: ${if (model.trafficAdjustmentGb > 0) "+" else ""}${FormatUtils.formatTrafficGb(model.trafficAdjustmentGb)}${stringResource(
                                id = R.string.gb_abbr,
                            )}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                }
            }

            // Action: Reset
            TextButton(
                onClick = onResetCounters,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(text = stringResource(id = R.string.reset_counters))
            }

            // Auto-Reset Section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
            ) {
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
                        Modifier
                            .minimumInteractiveComponentSize()
                            .scale(0.8f)
                            .semantics {
                                contentDescription = toggleAutoResetDesc
                            },
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
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


