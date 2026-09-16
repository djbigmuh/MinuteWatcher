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
interface WidgetSettingsDataModule {
    @Binds
    @Singleton
    fun bindSimWidgetVisibilityRepository(impl: SimWidgetVisibilityRepositoryImpl): SimWidgetVisibilityRepository

    @Binds
    @Singleton
    fun bindWidgetSettingsLoader(impl: WidgetSettingsRepository): WidgetSettingsLoader
}
