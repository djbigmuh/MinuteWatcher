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
 * Hilt module for widget.
 * Provides WidgetUpdater and WidgetRenderer.
 *
 * Sole creation point for WidgetRenderer.
 * Default parameter in WidgetUpdaterImpl constructor removed
 * to eliminate duplication and risk of implementation divergence.
 *
 * Strict API compliance: rendering mode (simplified / full)
 * is determined inside MinutesGlanceWidget.provideGlance.
 * External flags are not passed, eliminating race conditions.
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
