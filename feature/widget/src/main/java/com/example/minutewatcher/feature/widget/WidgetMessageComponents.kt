package com.example.minutewatcher.feature.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.resources.R

/**
 * Рендерит индикатор устаревших данных.
 * Показывается при refreshFailed = true.
 * Содержит иконку предупреждения и текст "Данные могут быть устаревшими".
 *
 * @param color Цвет индикатора.
 */
@Composable
internal fun StaleDataIndicator(color: ColorProvider) {
    val context = LocalContext.current
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
            text = context.getString(R.string.stale_data_warning),
            style =
                TextStyle(
                    fontSize = 10.sp,
                    color = color,
                ),
        )
    }
}

/**
 * Рендерит сообщение в виджете.
 * Используется для сообщений об ошибке, отсутствии SIM и т. д.
 *
 * @param text Текст сообщения.
 * @param color Цвет текста.
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
                fontSize =
                    Constants.Time.MILLIS_IN_SECOND
                        .let { Constants.Widget.FONT_SIZE_MEDIUM }
                        .sp,
                color = color,
            ),
    )
}
