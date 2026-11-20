package com.github.djbigmuh.minutewatcher.core.data.di

import com.github.djbigmuh.minutewatcher.core.data.MockSimProvider
import com.github.djbigmuh.minutewatcher.core.data.NoOpMockSimProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// Hilt DI module: instantiated via KSP code generation and reflection at runtime by Hilt framework.
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
interface MockSimModule {
    @Binds
    @Singleton
    fun bindMockSimProvider(impl: NoOpMockSimProvider): MockSimProvider
}
