package com.example.minutewatcher.core.common.di

import com.example.minutewatcher.core.common.util.SystemTimeProvider
import com.example.minutewatcher.core.common.util.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {
    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider
}
