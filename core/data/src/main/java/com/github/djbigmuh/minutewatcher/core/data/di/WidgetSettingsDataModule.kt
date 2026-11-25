package com.github.djbigmuh.minutewatcher.core.data.di

import com.github.djbigmuh.minutewatcher.core.data.SimWidgetVisibilityRepositoryImpl
import com.github.djbigmuh.minutewatcher.core.data.WidgetSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.github.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Suppress("unused")
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
