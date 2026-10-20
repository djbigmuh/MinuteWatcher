package host.serv.djbigmuh.minutewatcher.feature.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import javax.inject.Inject

// The sole implementation of WidgetRenderer. Created via Hilt.
// Manual creation of WidgetUpdaterImpl with a different renderer is forbidden:
// this would lead to divergence in widget rendering implementations.
class GlanceWidgetRenderer
    @Inject
    constructor() : WidgetRenderer {
        override suspend fun renderAll(
            context: Context,
            forceFull: Boolean,
        ) {
            MinutesGlanceWidget(forceFullRender = forceFull).updateAll(context)
        }
    }
