package com.example.minutewatcher.feature.settings.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.minutewatcher.core.android.ui.SimLayoutRenderer
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R

@Composable
fun WidgetPreview(
    uiState: WidgetUiState,
    modifier: Modifier = Modifier,
) {
    val alpha = 1f - uiState.transparency / 100f
    val bgColor = Color(uiState.backgroundColor).copy(alpha = alpha)

    val luminance =
        androidx.core.graphics.ColorUtils
            .calculateLuminance(uiState.backgroundColor)

    val noDataColor =
        if (uiState.useSystemColors) {
            androidx.compose.material3.MaterialTheme.colorScheme.onSurface
        } else {
            if (luminance > 0.5f) Color.Black else Color.White
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        val simUiStates = uiState.simUiStates.filter { it.showSim }

        if (simUiStates.isEmpty()) {
            Text(
                text = stringResource(id = R.string.no_data),
                color = noDataColor,
                fontSize = 14.sp,
            )
        } else {
            MultiSimPreviewLayout(simUiStates, uiState)
        }
    }
}

@Composable
private fun MultiSimPreviewLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    if (uiState.isHorizontal) {
        Row(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sims.forEachIndexed { index, sim ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    SimItemPreview(sim, uiState)
                }
                if (uiState.showSeparator && index < sims.size - 1) {
                    val separatorColor =
                        if (uiState.useSystemColors) {
                            androidx.compose.material3.MaterialTheme.colorScheme.outline
                        } else {
                            Color(sims[0].secondaryTextColor).copy(alpha = 0.5f)
                        }
                    Spacer(
                        modifier =
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(separatorColor),
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sims.forEachIndexed { index, sim ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    SimItemPreview(sim, uiState)
                }
                if (uiState.showSeparator && index < sims.size - 1) {
                    val separatorColor =
                        if (uiState.useSystemColors) {
                            androidx.compose.material3.MaterialTheme.colorScheme.outline
                        } else {
                            Color(sims[0].secondaryTextColor).copy(alpha = 0.5f)
                        }
                    Spacer(
                        modifier =
                            Modifier
                                .height(1.dp)
                                .fillMaxWidth()
                                .background(separatorColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun SimItemPreview(
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    val summary = stringResource(id = R.string.sim_status_summary, sim.operatorName, sim.minutesText, sim.trafficText)

    Column(
        modifier =
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = summary
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SimLayoutRenderer.Render(
            spec = sim.layoutSpec,
            rowContainer = { content ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    content()
                }
            },
            iconRenderer = { component ->
                Image(
                    painter = painterResource(id = component.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(component.sizeDp.dp),
                )
            },
            textRenderer = { component ->
                val textColor =
                    if (uiState.useSystemColors && component.color == sim.textColor) {
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    } else if (uiState.useSystemColors && component.color == sim.secondaryTextColor) {
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Color(component.color)
                    }
                Text(
                    text = component.text,
                    color = textColor,
                    fontSize = component.fontSizeSp.sp,
                    fontWeight = if (component.isBold) FontWeight.Bold else FontWeight.Normal,
                )
            },
            spacerRenderer = { component ->
                if (component.widthDp > 0) {
                    Spacer(modifier = Modifier.width(component.widthDp.dp))
                }
                if (component.heightDp > 0) {
                    Spacer(modifier = Modifier.height(component.heightDp.dp))
                }
            },
        )
    }
}
