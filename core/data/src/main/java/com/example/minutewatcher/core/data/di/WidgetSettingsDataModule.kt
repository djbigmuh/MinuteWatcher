package com.example.minutewatcher.core.data.di

import com.example.minutewatcher.core.data.SimWidgetVisibilityRepositoryImpl
import com.example.minutewatcher.core.data.WidgetSettingsRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetSettingsDataModule {
    @Binds
    @Singleton
    abstract fun bindSimWidgetVisibilityRepository(
        impl: SimWidgetVisibilityRepositoryImpl,
    ): SimWidgetVisibilityRepository

    @Binds
    @Singleton
    abstract fun bindWidgetSettingsLoader(impl: WidgetSettingsRepository): WidgetSettingsLoader
}
