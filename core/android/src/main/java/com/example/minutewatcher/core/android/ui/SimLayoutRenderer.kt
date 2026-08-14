package com.example.minutewatcher.core.android.ui

import androidx.compose.runtime.Composable
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimLayoutSpec

/**
 * Renders a [SimLayoutSpec] using the provided renderer lambdas.
 * This allows sharing the iteration logic between standard Compose and Glance.
 *
 * IMPORTANT: The order and signature of parameters MUST be consistent to avoid
 * NoSuchMethodError during runtime, especially when used across different modules.
 */
@Composable
fun SimLayoutRenderV2(
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
