package com.github.djbigmuh.minutewatcher.core.data.di

import com.github.djbigmuh.minutewatcher.core.data.SimNotificationFlagsRepositoryImpl
import com.github.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
interface NotificationFlagsDataModule {
    @Binds
    @Singleton
    fun bindSimNotificationFlagsRepository(impl: SimNotificationFlagsRepositoryImpl): SimNotificationFlagsRepository
}
