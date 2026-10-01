package com.example.minutewatcher.core.data.di

import com.example.minutewatcher.core.data.BenchmarkMockSimProvider
import com.example.minutewatcher.core.data.MockSimProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface MockSimModule {
    @Binds
    @Singleton
    fun bindMockSimProvider(impl: BenchmarkMockSimProvider): MockSimProvider
}
