package com.github.djbigmuh.minutewatcher.feature.widget.di

import com.github.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import com.github.djbigmuh.minutewatcher.feature.widget.GlanceWidgetRenderer
import com.github.djbigmuh.minutewatcher.feature.widget.WidgetRenderer
import com.github.djbigmuh.minutewatcher.feature.widget.WidgetUpdaterImpl
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
 *
 * Strict API compliance: rendering mode (simplified / full)
 * is determined inside MinutesGlanceWidget.provideGlance.
 * External flags are not passed, eliminating race conditions.
 */
@Module
@InstallIn(SingletonComponent::class)
interface WidgetModule {
    @Binds
    @Singleton
    fun bindWidgetUpdater(impl: WidgetUpdaterImpl): WidgetUpdater

    @Binds
    @Singleton
    fun bindWidgetRenderer(impl: GlanceWidgetRenderer): WidgetRenderer
}
