package com.example.minutewatcher.di

import com.example.minutewatcher.ActivityStarter
import com.example.minutewatcher.ContextActivityStarter
import com.example.minutewatcher.NavigationProviderImpl
import com.example.minutewatcher.core.domain.NavigationProvider
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
}
