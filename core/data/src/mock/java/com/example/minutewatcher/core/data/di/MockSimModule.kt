package com.example.minutewatcher.core.data.di

import com.example.minutewatcher.core.data.DebugMockSimProvider
import com.example.minutewatcher.core.data.MockSimProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
// @Suppress("unused") is required because Hilt discovers bindings via reflection.
@Suppress("unused")
abstract class MockSimModule {
    @Binds
    @Singleton
    abstract fun bindMockSimProvider(impl: DebugMockSimProvider): MockSimProvider
}
