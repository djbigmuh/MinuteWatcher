package com.example.minutewatcher.feature.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.minutewatcher.core.android.ui.SimLayoutRenderV2
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimUiState
import com.example.minutewatcher.core.model.WidgetUiState
import com.example.minutewatcher.core.resources.R

/**
 * Рендерит одну SIM-карту в виджете.
 * Содержит иконку, имя оператора, состояние минут и трафика.
 * Использует SimLayoutRenderV2 для рендера.
 *
 * @param sim Состояние SIM-карты.
 * @param uiState Состояние виджета.
 */
@Composable
internal fun SimItem(
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    val context = LocalContext.current
    val summary =
        context.getString(
            R.string.sim_status_summary,
            sim.operatorName,
            sim.minutesText,
            sim.trafficText,
        )

    Column(
        modifier =
            GlanceModifier
                .padding(Constants.Widget.PADDING_SIM_ITEM.dp)
                .semantics { contentDescription = summary },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimItemLayout(sim, uiState)
    }
}

/**
 * Рендерит layout SIM-карты через SimLayoutRenderV2.
 * Передаёт рендереры для иконки, текста и spacer.
 *
 * @param sim Состояние SIM-карты.
 * @param uiState Состояние виджета.
 */
@Composable
internal fun SimItemLayout(
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    SimLayoutRenderV2(
        spec = sim.layoutSpec,
        iconRenderer = { component ->
            SimItemIcon(component)
        },
        textRenderer = { component ->
            SimItemText(component, sim, uiState)
        },
        spacerRenderer = { component ->
            SimItemSpacer(component)
        },
    ) { content ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}

/**
 * Рендерит иконку SIM-карт в виджете.
 * Используется в SimLayoutRenderV2.
 *
 * @param component Компонент иконки (iconRes, sizeDp).
 */
@Composable
internal fun SimItemIcon(component: SimLayoutComponent.Icon) {
    Image(
        provider = ImageProvider(component.iconRes),
        contentDescription = null,
        modifier = GlanceModifier.size(component.sizeDp.dp),
    )
}

/**
 * Рендерит текст SIM-карты в виджете.
 * Используется в SimLayoutRenderV2.
 * Цвет текста определяется в зависимости от useSystemColors.
 *
 * @param component Компонент текста (text, color, fontSizeSp, isBold).
 * @param sim Состояние SIM-карты.
 * @param uiState Состояние виджета.
 */
@Composable
internal fun SimItemText(
    component: SimLayoutComponent.Text,
    sim: SimUiState,
    uiState: WidgetUiState,
) {
    val textColor = resolveTextColor(uiState, sim, component)

    Text(
        text = component.text,
        style =
            TextStyle(
                fontSize = component.fontSizeSp.sp,
                fontWeight = if (component.isBold) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
            ),
    )
}

@Composable
private fun resolveTextColor(
    uiState: WidgetUiState,
    sim: SimUiState,
    component: SimLayoutComponent.Text,
): ColorProvider =
    if (uiState.useSystemColors && (component.color == sim.textColor)) {
        GlanceTheme.colors.onSurface
    } else if (uiState.useSystemColors && (component.color == sim.secondaryTextColor)) {
        GlanceTheme.colors.onSurfaceVariant
    } else {
        val composeColor =
            Color(component.color)
        androidx.glance.color.ColorProvider(day = composeColor, night = composeColor)
    }

/**
 * Рендерит spacer в виджете.
 * Используется в SimLayoutRenderV2.
 *
 * @param component Компонент spacer (widthDp, heightDp).
 */
@Composable
internal fun SimItemSpacer(component: SimLayoutComponent.Spacer) {
    if (component.widthDp > 0) {
        Spacer(modifier = GlanceModifier.width(component.widthDp.dp))
    }
    if (component.heightDp > 0) {
        Spacer(modifier = GlanceModifier.height(component.heightDp.dp))
    }
}
