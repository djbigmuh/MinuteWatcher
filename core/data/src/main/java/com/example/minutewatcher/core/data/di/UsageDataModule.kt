package com.example.minutewatcher.core.data.di

import com.example.minutewatcher.core.data.CallTracker
import com.example.minutewatcher.core.data.DataRefreshNotifierImpl
import com.example.minutewatcher.core.data.ResetManager
import com.example.minutewatcher.core.data.SimLimitsRepositoryImpl
import com.example.minutewatcher.core.data.SimUsageRepositoryImpl
import com.example.minutewatcher.core.data.TrafficTracker
import com.example.minutewatcher.core.data.UpdateCoordinator
import com.example.minutewatcher.core.data.UsageStateRepositoryImpl
import com.example.minutewatcher.core.data.UsageUseCaseImpl
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimUsageAdjustmentRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimUsageResetRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UsageDataModule {
    @Binds
    @Singleton
    abstract fun bindSimUsageRepository(impl: SimUsageRepositoryImpl): SimUsageRepository

    @Binds
    @Singleton
    abstract fun bindSimUsageResetRepository(impl: SimUsageRepositoryImpl): SimUsageResetRepository

    @Binds
    @Singleton
    abstract fun bindSimUsageAdjustmentRepository(impl: SimUsageRepositoryImpl): SimUsageAdjustmentRepository

    @Binds
    @Singleton
    abstract fun bindSimLimitsRepository(impl: SimLimitsRepositoryImpl): SimLimitsRepository

    @Binds
    @Singleton
    abstract fun bindUsageStateRepository(impl: UsageStateRepositoryImpl): UsageStateRepository

    @Binds
    @Singleton
    abstract fun bindAutoResetUseCase(impl: ResetManager): AutoResetUseCase

    @Binds
    @Singleton
    abstract fun bindUsageUseCase(impl: UsageUseCaseImpl): UsageUseCase

    @Binds
    @Singleton
    abstract fun bindDataRefreshNotifier(impl: DataRefreshNotifierImpl): DataRefreshNotifier

    @Binds
    @Singleton
    abstract fun bindDataChangeNotifier(impl: UpdateCoordinator): DataChangeNotifier

    @Binds
    @IntoSet
    abstract fun bindCallTrackerStatus(impl: CallTracker): TrackingStatusProvider

    @Binds
    @IntoSet
    abstract fun bindTrafficTrackerStatus(impl: TrafficTracker): TrackingStatusProvider
}
