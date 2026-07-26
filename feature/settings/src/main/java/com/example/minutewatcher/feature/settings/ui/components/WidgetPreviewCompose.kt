package com.example.minutewatcher.feature.settings.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.minutewatcher.core.model.*
import com.example.minutewatcher.core.model.R

@Composable
fun WidgetPreview(
    uiState: WidgetUiState,
    modifier: Modifier = Modifier,
) {
    val alpha = uiState.transparency / 100f
    val bgColor = Color(uiState.backgroundColor).copy(alpha = alpha)

    val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(uiState.backgroundColor)
    val noDataColor = if (luminance > 0.5f) Color.Black else Color.White

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        val simUiStates = uiState.simUiStates.filter { it.showSim }
        val layoutMode = WidgetLayoutMode.fromValue(uiState.layoutMode)

        if (simUiStates.isEmpty()) {
            Text(
                text = stringResource(id = R.string.no_data),
                color = noDataColor,
                fontSize = 14.sp,
            )
        } else {
            val isHorizontal = uiState.simArrangement == 0
            if (isHorizontal && simUiStates.size >= 2) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        SimItemPreview(simUiStates[0], layoutMode)
                    }
                    Spacer(
                        modifier =
                            Modifier
                                .width(
                                    1.dp,
                                ).fillMaxHeight()
                                .background(Color(simUiStates[0].secondaryTextColor).copy(alpha = 0.5f)),
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        SimItemPreview(simUiStates[1], layoutMode)
                    }
                }
            } else if (!isHorizontal && simUiStates.size >= 2) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        SimItemPreview(simUiStates[0], layoutMode)
                    }
                    Spacer(
                        modifier =
                            Modifier
                                .height(
                                    1.dp,
                                ).fillMaxWidth()
                                .background(Color(simUiStates[0].secondaryTextColor).copy(alpha = 0.5f)),
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        SimItemPreview(simUiStates[1], layoutMode)
                    }
                }
            } else {
                Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                    SimItemPreview(simUiStates[0], layoutMode)
                }
            }
        }
    }
}

@Composable
private fun SimItemPreview(
    sim: SimUiState,
    layoutMode: WidgetLayoutMode,
) {
    val fontMultiplier = sim.fontMultiplier
    val textColor = Color(sim.textColor)
    val secondaryColor = Color(sim.secondaryTextColor)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (layoutMode) {
            WidgetLayoutMode.SINGLE_LINE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sim.showIcon) {
                        Image(
                            painter = painterResource(id = sim.operatorIconRes),
                            contentDescription = null,
                            modifier =
                                Modifier.size(
                                    (
                                        14 *
                                            fontMultiplier
                                    ).dp,
                                ),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (sim.showName) {
                        Text(text = sim.operatorName, color = textColor, fontSize = (10 * fontMultiplier).sp)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (sim.showMinutes) {
                        Text(
                            text = sim.minutesText,
                            color = Color(sim.minutesColor),
                            fontWeight = FontWeight.Bold,
                            fontSize =
                                (
                                    12 *
                                        fontMultiplier
                                ).sp,
                        )
                        Text(
                            text = stringResource(id = R.string.minutes_abbr),
                            color = secondaryColor,
                            fontSize = (10 * fontMultiplier).sp,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                    if (sim.showTraffic) {
                        Text(
                            text = sim.trafficText,
                            color = Color(sim.trafficColor),
                            fontWeight = FontWeight.Bold,
                            fontSize =
                                (
                                    12 *
                                        fontMultiplier
                                ).sp,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        Text(
                            text = stringResource(id = R.string.gb_abbr),
                            color = secondaryColor,
                            fontSize = (10 * fontMultiplier).sp,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
            }
            WidgetLayoutMode.TWO_LINES -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (sim.showIcon) {
                            Image(
                                painter = painterResource(id = sim.operatorIconRes),
                                contentDescription = null,
                                modifier =
                                    Modifier.size(
                                        (
                                            16 *
                                                fontMultiplier
                                        ).dp,
                                    ),
                            )
                        }
                        if (sim.showName) Text(text = sim.operatorName, color = textColor, fontSize = (10 * fontMultiplier).sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (sim.showMinutes) {
                        Text(
                            text = sim.minutesText,
                            color = Color(sim.minutesColor),
                            fontWeight = FontWeight.Bold,
                            fontSize =
                                (
                                    14 *
                                        fontMultiplier
                                ).sp,
                        )
                        Text(
                            text = stringResource(id = R.string.minutes_abbr),
                            color = secondaryColor,
                            fontSize = (10 * fontMultiplier).sp,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                    if (sim.showTraffic) {
                        Text(
                            text = sim.trafficText,
                            color = Color(sim.trafficColor),
                            fontWeight = FontWeight.Bold,
                            fontSize =
                                (
                                    14 *
                                        fontMultiplier
                                ).sp,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        Text(
                            text = stringResource(id = R.string.gb_abbr),
                            color = secondaryColor,
                            fontSize = (10 * fontMultiplier).sp,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
            }
            WidgetLayoutMode.THREE_LINES -> {
                if (sim.showIcon || sim.showName) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sim.showIcon) {
                            Image(
                                painter = painterResource(id = sim.operatorIconRes),
                                contentDescription = null,
                                modifier =
                                    Modifier.size(
                                        (
                                            16 *
                                                fontMultiplier
                                        ).dp,
                                    ),
                            )
                        }
                        if (sim.showName) {
                            Text(
                                text = sim.operatorName,
                                color = textColor,
                                fontSize = (10 * fontMultiplier).sp,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
                if (sim.showMinutes) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sim.minutesText,
                            color = Color(sim.minutesColor),
                            fontWeight = FontWeight.Bold,
                            fontSize =
                                (
                                    14 *
                                        fontMultiplier
                                ).sp,
                        )
                        if (sim.showMinutesLimit) {
                            Text(
                                text = sim.minutesLimitText,
                                color = secondaryColor,
                                fontSize = (10 * fontMultiplier).sp,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                        Text(
                            text = stringResource(id = R.string.minutes_abbr),
                            color = secondaryColor,
                            fontSize = (10 * fontMultiplier).sp,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
                if (sim.showTraffic) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sim.trafficText,
                            color = Color(sim.trafficColor),
                            fontWeight = FontWeight.Bold,
                            fontSize =
                                (
                                    14 *
                                        fontMultiplier
                                ).sp,
                        )
                        if (sim.showTrafficLimit) {
                            Text(
                                text = sim.trafficLimitText,
                                color = secondaryColor,
                                fontSize = (10 * fontMultiplier).sp,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                        Text(
                            text = stringResource(id = R.string.gb_abbr),
                            color = secondaryColor,
                            fontSize = (10 * fontMultiplier).sp,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
            }
            WidgetLayoutMode.SIX_LINES -> {
                // Line 1: Name
                Text(
                    text = sim.operatorName,
                    color = textColor,
                    fontSize = (12 * fontMultiplier).sp
                )
                // Line 2: Icon
                Image(
                    painter = painterResource(id = sim.operatorIconRes),
                    contentDescription = null,
                    modifier = Modifier.size((16 * fontMultiplier).dp)
                )
                // Line 3: Minutes Used
                Text(
                    text = sim.minutesUsedFullText,
                    color = textColor,
                    fontSize = (11 * fontMultiplier).sp
                )
                // Line 4: Minutes Limit
                Text(
                    text = sim.minutesLimitFullText,
                    color = secondaryColor,
                    fontSize = (11 * fontMultiplier).sp
                )
                // Line 5: Traffic Used
                Text(
                    text = sim.trafficUsedFullText,
                    color = textColor,
                    fontSize = (11 * fontMultiplier).sp
                )
                // Line 6: Traffic Limit
                Text(
                    text = sim.trafficLimitFullText,
                    color = secondaryColor,
                    fontSize = (11 * fontMultiplier).sp
                )
            }
        }
    }
}
