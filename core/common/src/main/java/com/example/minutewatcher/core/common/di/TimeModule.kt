package com.example.minutewatcher.core.common.di

import com.example.minutewatcher.core.common.util.SystemTimeProvider
import com.example.minutewatcher.core.common.util.TimeProvider
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
