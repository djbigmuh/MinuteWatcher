package com.example.minutewatcher.core.data.di

import com.example.minutewatcher.core.data.MockSimProvider
import com.example.minutewatcher.core.data.ProductionMockSimProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
interface MockSimModule {
    @Binds
    @Singleton
    fun bindMockSimProvider(impl: ProductionMockSimProvider): MockSimProvider
}
