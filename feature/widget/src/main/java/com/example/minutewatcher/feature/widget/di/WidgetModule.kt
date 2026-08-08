package com.example.minutewatcher.feature.widget.di

import com.example.minutewatcher.core.domain.WidgetUpdater
import com.example.minutewatcher.feature.widget.WidgetUpdaterImpl
import dagger.Binds
import dagger.Module
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
}
