package com.example.minutewatcher.core.data.di

import com.example.minutewatcher.core.data.MockSimProvider
import com.example.minutewatcher.core.data.NoOpMockSimProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class MockSimModule {
    @Binds
    @Singleton
    abstract fun bindMockSimProvider(impl: NoOpMockSimProvider): MockSimProvider
}
