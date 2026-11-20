package com.github.djbigmuh.minutewatcher.di

import com.github.djbigmuh.minutewatcher.ActivityStarter
import com.github.djbigmuh.minutewatcher.AppInfoProviderImpl
import com.github.djbigmuh.minutewatcher.ContextActivityStarter
import com.github.djbigmuh.minutewatcher.NavigationProviderImpl
import com.github.djbigmuh.minutewatcher.core.domain.AppInfoProvider
import com.github.djbigmuh.minutewatcher.core.domain.NavigationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface NavigationModule {
    @Binds
    @Singleton
    fun bindNavigationProvider(impl: NavigationProviderImpl): NavigationProvider

    @Binds
    @Singleton
    fun bindActivityStarter(impl: ContextActivityStarter): ActivityStarter

    @Binds
    @Singleton
    fun bindAppInfoProvider(impl: AppInfoProviderImpl): AppInfoProvider
}
