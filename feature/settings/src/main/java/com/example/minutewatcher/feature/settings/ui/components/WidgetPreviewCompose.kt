package com.example.minutewatcher.feature.settings.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.minutewatcher.core.android.ui.SimLayoutRenderV2
import com.example.minutewatcher.core.android.util.WidgetColorResolver
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R

@Composable
fun WidgetPreview(
    uiState: WidgetUiState,
    modifier: Modifier = Modifier,
) {
    val alpha = Constants.Widget.ALPHA_FULL - uiState.transparency / Constants.Widget.PERCENT_MAX_FLOAT
    val bgColor = Color(uiState.backgroundColor).copy(alpha = alpha)
    val noDataColor = getNoDataColor(uiState)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clip(RoundedCornerShape(Constants.Widget.CORNER_RADIUS.dp))
                .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        WidgetPreviewContent(uiState, noDataColor)
    }
}

@Composable
private fun WidgetPreviewContent(
    uiState: WidgetUiState,
    noDataColor: Color,
) {
    val simUiStates = uiState.simUiStates.filter { it.showSim }

    if (simUiStates.isEmpty()) {
        Text(
            text = stringResource(id = R.string.no_data),
            color = noDataColor,
            fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
        )
    } else {
        MultiSimPreviewLayout(simUiStates, uiState)
    }
}

@Composable
private fun getNoDataColor(uiState: WidgetUiState): Color =
    if (uiState.useSystemColors) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color(WidgetColorResolver.resolveNoDataColor(uiState.backgroundColor))
    }

@Composable
private fun MultiSimPreviewLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    if (uiState.isHorizontal) {
        HorizontalSimPreviewLayout(sims, uiState)
    } else {
        VerticalSimPreviewLayout(sims, uiState)
    }
}

@Composable
private fun HorizontalSimPreviewLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .wrapContentSize()
                    .padding(Constants.Widget.PADDING_DEFAULT.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sims.forEachIndexed { index, sim ->
                Box(modifier = Modifier.wrapContentSize(), contentAlignment = Alignment.Center) {
                    SimItemPreview(sim, uiState)
                }
                if (uiState.showSeparator && index < sims.size - 1) {
                    Separator(isHorizontal = true, uiState = uiState, firstSim = sims[0])
                }
            }
        }
    }
}

@Composable
private fun VerticalSimPreviewLayout(
    sims: List<SimUiState>,
    uiState: WidgetUiState,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .wrapContentSize()
                    .padding(Constants.Widget.PADDING_DEFAULT.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sims.forEachIndexed { index, sim ->
                Box(modifier = Modifier.wrapContentSize(), contentAlignment = Alignment.Center) {
                    SimItemPreview(sim, uiState)
                }
                if (uiState.showSeparator && index < sims.size - 1) {
                    Separator(isHorizontal = false, uiState = uiState, firstSim = sims[0])
                }
            }
        }
    }
}

@Composable
private fun Separator(
    isHorizontal: Boolean,
    uiState: WidgetUiState,
    firstSim: SimUiState,
) {
    val separatorColor =
        if (uiState.useSystemColors) {
            MaterialTheme.colorScheme.outline
        } else {
            Color(firstSim.secondaryTextColor).copy(alpha = Constants.Widget.SEPARATOR_ALPHA)
        }
    val modifier =
        if (isHorizontal) {
            Modifier
                .width(Constants.Widget.SEPARATOR_THICKNESS.dp)
                .padding(vertical = 8.dp)
        } else {
            Modifier
                .height(Constants.Widget.SEPARATOR_THICKNESS.dp)
                .padding(horizontal = 24.dp)
        }
    // Use Box with minimum size via padding to imitate "content" without fillMax*
    Box(
        modifier =
            modifier
                .background(separatorColor)
                .defaultMinSize(
                    minWidth = if (isHorizontal) Constants.Widget.SEPARATOR_THICKNESS.dp else 48.dp,
                    minHeight = if (isHorizontal) 32.dp else Constants.Widget.SEPARATOR_THICKNESS.dp,
                ),
    )
}

@Composable
private fun SimItemPreview(
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    val summary = stringResource(id = R.string.sim_status_summary, sim.operatorName, sim.minutesText, sim.trafficText)

    Column(
        modifier =
            Modifier
                .wrapContentSize()
                .semantics(mergeDescendants = true) {
                    contentDescription = summary
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SimLayoutRenderV2(
            spec = sim.layoutSpec,
            iconRenderer = { component ->
                Image(
                    painter = painterResource(id = component.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(component.sizeDp.dp),
                )
            },
            textRenderer = { component ->
                val textColor = getTextColor(uiState, sim, component)
                Text(
                    text = component.text,
                    color = textColor,
                    fontSize = component.fontSizeSp.sp,
                    fontWeight = if (component.isBold) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            rowContainer = { content ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    content()
                }
            },
        )
    }
}

@Composable
private fun getTextColor(
    uiState: WidgetUiState,
    sim: SimUiState,
    component: SimLayoutComponent.Text,
): Color =
    if (uiState.useSystemColors && component.color == sim.textColor) {
        MaterialTheme.colorScheme.onSurface
    } else if (uiState.useSystemColors && component.color == sim.secondaryTextColor) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color(component.color)
    }
