package com.github.djbigmuh.minutewatcher.feature.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.resources.R

/**
 * Renders stale data indicator.
 * Shown when refreshFailed = true.
 * Contains warning icon and text "Data may be stale".
 *
 * @param color Indicator color.
 * @param text Localized warning text.
 */
@Composable
internal fun StaleDataIndicator(
    color: ColorProvider,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier.padding(top = 4.dp),
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_warning_amber),
            contentDescription = null,
            modifier = GlanceModifier.size(12.dp),
            colorFilter = ColorFilter.tint(color),
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = text,
            style =
                TextStyle(
                    fontSize = 10.sp,
                    color = color,
                ),
        )
    }
}

/**
 * Renders a message in widget.
 * Used for error messages, no SIM messages, etc.
 *
 * @param text Message text.
 * @param color Text color.
 */
@Composable
internal fun WidgetMessage(
    text: String,
    color: ColorProvider,
) {
    Text(
        text = text,
        style =
            TextStyle(
                fontSize = Constants.Widget.FONT_SIZE_MEDIUM.sp,
                color = color,
            ),
    )
}
