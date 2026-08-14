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
         * Дефолтная реализация [WidgetRenderer], используемая при отсутствии кастомного биндинга.
         *
         * ВАЖНО: Строгое соблюдение контракта интерфейса обязательно. Несовпадение сигнатуры
         * (например, отсутствие параметра forceFull) является ошибкой компиляции и причиной краша.
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
