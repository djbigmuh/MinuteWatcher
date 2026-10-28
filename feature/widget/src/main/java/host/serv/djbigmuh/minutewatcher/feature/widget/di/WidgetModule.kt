package host.serv.djbigmuh.minutewatcher.feature.widget.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdater
import host.serv.djbigmuh.minutewatcher.feature.widget.GlanceWidgetRenderer
import host.serv.djbigmuh.minutewatcher.feature.widget.WidgetRenderer
import host.serv.djbigmuh.minutewatcher.feature.widget.WidgetUpdaterImpl
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
