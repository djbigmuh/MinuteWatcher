package com.example.minutewatcher.feature.widget.di

import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.feature.widget.GlanceWidgetRenderer
import com.example.minutewatcher.feature.widget.WidgetRenderer
import com.example.minutewatcher.feature.widget.WidgetUpdaterImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt модуль для виджета.
 * Предоставляет WidgetUpdater и WidgetRenderer.
 *
 * Единственная точка создания WidgetRenderer.
 * Default-параметр в constructor WidgetUpdaterImpl удалён,
 * чтобы исключить дублирование и риск расхождения реализаций.
 *
 * Строгое соблюдение API: режим рендера (упрощённый / полный)
 * определяется внутри MinutesGlanceWidget.provideGlance.
 * Внешние флаги не передаются, что исключает race condition.
 */
@Module
@InstallIn(SingletonComponent::class)
// @Suppress("unused") is required because Hilt discovers bindings via reflection.
@Suppress("unused")
interface WidgetModule {
    @Binds
    @Singleton
    fun bindWidgetUpdater(impl: WidgetUpdaterImpl): WidgetUpdater

    @Binds
    @Singleton
    fun bindWidgetRenderer(impl: GlanceWidgetRenderer): WidgetRenderer
}
