package com.example.minutewatcher.core.data.di

import com.example.minutewatcher.core.data.SimNotificationFlagsRepositoryImpl
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationFlagsDataModule {
    @Binds
    @Singleton
    abstract fun bindSimNotificationFlagsRepository(impl: SimNotificationFlagsRepositoryImpl): SimNotificationFlagsRepository
}
