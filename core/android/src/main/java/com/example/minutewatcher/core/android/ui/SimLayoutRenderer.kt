package com.example.minutewatcher.core.android.ui

import androidx.compose.runtime.Composable
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimLayoutSpec

/**
 * Renders SimLayoutSpec via provided renderers.
 * Allows reusing iteration logic between Compose and Glance.
 *
 * Widget preview contract:
 * Preview output MUST be identical to the real widget
 * given the same SimLayoutSpec and visibility settings.
 * Preview is NOT a simplified version. If the user sees
 * suffix or limit in the preview, they MUST see it in the real widget too.
 * Any discrepancy between preview and widget is a bug.
 *
 * IMPORTANT: Parameter order and signature MUST be consistent
 * to prevent NoSuchMethodError at runtime, especially when used across modules.
 *
 * @param spec Layout specification.
 * @param iconRenderer Icon renderer.
 * @param textRenderer Text renderer.
 * @param spacerRenderer Spacer renderer.
 * @param rowContainer Row container.
 */
@Composable
fun SimLayoutRender(
    spec: SimLayoutSpec,
    iconRenderer: @Composable (SimLayoutComponent.Icon) -> Unit,
    textRenderer: @Composable (SimLayoutComponent.Text) -> Unit,
    spacerRenderer: @Composable (SimLayoutComponent.Spacer) -> Unit,
    rowContainer: @Composable (content: @Composable () -> Unit) -> Unit,
) {
    spec.rows.forEach { row ->
        rowContainer {
            row.components.forEach { component ->
                when (component) {
                    is SimLayoutComponent.Icon -> iconRenderer(component)
                    is SimLayoutComponent.Text -> textRenderer(component)
                    is SimLayoutComponent.Spacer -> spacerRenderer(component)
                }
            }
        }
    }
}
