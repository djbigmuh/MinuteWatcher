package com.example.minutewatcher.di

import com.example.minutewatcher.NavigationProviderImpl
import com.example.minutewatcher.core.domain.NavigationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindNavigationProvider(impl: NavigationProviderImpl): NavigationProvider
}
