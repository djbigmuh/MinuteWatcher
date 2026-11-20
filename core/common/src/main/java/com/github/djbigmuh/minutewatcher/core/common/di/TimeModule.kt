package com.github.djbigmuh.minutewatcher.core.common.di

import com.github.djbigmuh.minutewatcher.core.common.util.SystemTimeProvider
import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    @Provides
    @Singleton
    fun providesTimeProvider(): TimeProvider = SystemTimeProvider()
}
