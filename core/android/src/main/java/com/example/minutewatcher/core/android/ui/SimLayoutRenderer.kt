package com.example.minutewatcher.core.android.ui

import androidx.compose.runtime.Composable
import com.example.minutewatcher.core.model.SimLayoutComponent
import com.example.minutewatcher.core.model.SimLayoutSpec

/**
 * Рендерит SimLayoutSpec через переданные рендереры.
 * Позволяет переиспользовать логику итерации между Compose и Glance.
 *
 * Контракт превью виджета:
 * Визуальный вывод превью ДОЛЖЕН быть идентичен реальному виджету
 * при одинаковом SimLayoutSpec и настройках видимости.
 * Превью НЕ является упрощённой версией. Если пользователь видит
 * суффикс или лимит в превью, он ДОЛЖЕН видеть его и в реальном виджете.
 * Любое расхождение между превью и виджетом является багом.
 *
 * ВАЖНО: Порядок и сигнатура параметров ДОЛЖНЫ быть консистентны
 * для предотвращения NoSuchMethodError во время выполнения,
 * особенно при использовании между модулями.
 *
 * @param spec Спецификация layout.
 * @param iconRenderer Рендерер иконки.
 * @param textRenderer Рендерер текста.
 * @param spacerRenderer Рендерер spacer.
 * @param rowContainer Контейнер строки.
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
