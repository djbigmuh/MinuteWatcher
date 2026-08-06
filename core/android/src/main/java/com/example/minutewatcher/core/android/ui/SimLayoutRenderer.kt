package com.example.minutewatcher.core.android.ui

import androidx.compose.runtime.Composable
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimLayoutSpec

/**
 * Renders a [SimLayoutSpec] using the provided renderer lambdas.
 * This allows sharing the iteration logic between standard Compose and Glance.
 */
@Composable
fun SimLayoutRender(
    spec: SimLayoutSpec,
    rowContainer: @Composable (content: @Composable () -> Unit) -> Unit,
    iconRenderer: @Composable (SimLayoutComponent.Icon) -> Unit,
    textRenderer: @Composable (SimLayoutComponent.Text) -> Unit,
    spacerRenderer: @Composable (SimLayoutComponent.Spacer) -> Unit
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
