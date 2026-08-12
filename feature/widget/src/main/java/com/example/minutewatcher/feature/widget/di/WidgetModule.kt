package com.example.minutewatcher.feature.widget.di

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.feature.widget.MinutesGlanceWidget
import com.example.minutewatcher.feature.widget.WidgetRenderer
import com.example.minutewatcher.feature.widget.WidgetUpdaterImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
// Hilt discovers this module via reflection at compile time; IDE marks it as unused.
@Suppress("unused")
interface WidgetModule {
    @Binds
    @Singleton
    fun bindWidgetUpdater(impl: WidgetUpdaterImpl): WidgetUpdater

    companion object {
        @Provides
        @Singleton
        fun provideWidgetRenderer(): WidgetRenderer =
            object : WidgetRenderer {
                override suspend fun renderAll(context: Context) {
                    MinutesGlanceWidget().updateAll(context)
                }
            }
    }
}
