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
        /**
         * Единственная точка создания WidgetRenderer.
         * Default-параметр в constructor WidgetUpdaterImpl удалён,
         * чтобы исключить дублирование и риск расхождения реализаций.
         *
         * Строгое соблюдение API: сигнатура renderAll(context, forceSimplified, forceFull)
         * является контрактом интерфейса WidgetRenderer. Любое изменение сигнатуры
         * требует одновременного обновления всех реализаций и точек вызова
         * во избежание AbstractMethodError (бинарная несовместимость).
         */
        @Provides
        @Singleton
        fun provideWidgetRenderer(): WidgetRenderer =
            object : WidgetRenderer {
                override suspend fun renderAll(
                    context: Context,
                    forceSimplified: Boolean,
                    forceFull: Boolean,
                ) {
                    MinutesGlanceWidget.setForceSimplified(context, forceSimplified)
                    MinutesGlanceWidget.setForceFull(context, forceFull)
                    try {
                        MinutesGlanceWidget().updateAll(context)
                    } finally {
                        MinutesGlanceWidget.setForceSimplified(context, false)
                        MinutesGlanceWidget.setForceFull(context, false)
                    }
                }
            }
    }
}
