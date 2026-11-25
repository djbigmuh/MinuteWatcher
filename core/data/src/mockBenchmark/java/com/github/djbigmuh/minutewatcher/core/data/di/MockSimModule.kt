package com.github.djbigmuh.minutewatcher.core.data.di

import com.github.djbigmuh.minutewatcher.core.data.BenchmarkMockSimProvider
import com.github.djbigmuh.minutewatcher.core.data.BenchmarkSimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.data.BenchmarkUseCaseImpl
import com.github.djbigmuh.minutewatcher.core.data.MockSimProvider
import com.github.djbigmuh.minutewatcher.core.domain.BenchmarkUseCase
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
interface MockSimModule {
    @Binds
    @Singleton
    fun bindMockSimProvider(impl: BenchmarkMockSimProvider): MockSimProvider

    @Binds
    @Singleton
    fun bindBenchmarkUseCase(impl: BenchmarkUseCaseImpl): BenchmarkUseCase

    @Binds
    @Singleton
    fun bindSimIdentityRepository(impl: BenchmarkSimIdentityRepository): SimIdentityRepository
}
