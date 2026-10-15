package com.example.minutewatcher.core.data.di

import com.example.minutewatcher.core.data.CallTracker
import com.example.minutewatcher.core.data.DataRefreshNotifierImpl
import com.example.minutewatcher.core.data.NetworkStatsProvider
import com.example.minutewatcher.core.data.RealNetworkStatsProvider
import com.example.minutewatcher.core.data.ResetManager
import com.example.minutewatcher.core.data.SimIdentityObserver
import com.example.minutewatcher.core.data.SimLimitsRepositoryImpl
import com.example.minutewatcher.core.data.SimUsageRepositoryImpl
import com.example.minutewatcher.core.data.TrackingStatusRepositoryImpl
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
import com.example.minutewatcher.core.domain.TrackingStatusRepository
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.UsageUseCase
import com.example.minutewatcher.core.domain.WidgetUpdateCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Centralized Hilt module for binding data layer implementations to their domain interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface UsageDataModule {
    @Binds
    @Singleton
    fun bindSimUsageRepository(impl: SimUsageRepositoryImpl): SimUsageRepository

    @Binds
    @Singleton
    fun bindSimUsageResetRepository(impl: SimUsageRepositoryImpl): SimUsageResetRepository

    @Binds
    @Singleton
    fun bindSimUsageAdjustmentRepository(impl: SimUsageRepositoryImpl): SimUsageAdjustmentRepository

    @Binds
    @Singleton
    fun bindSimLimitsRepository(impl: SimLimitsRepositoryImpl): SimLimitsRepository

    @Binds
    @Singleton
    fun bindUsageStateRepository(impl: UsageStateRepositoryImpl): UsageStateRepository

    @Binds
    @Singleton
    fun bindTrackingStatusRepository(impl: TrackingStatusRepositoryImpl): TrackingStatusRepository

    @Binds
    @Singleton
    fun bindAutoResetUseCase(impl: ResetManager): AutoResetUseCase

    @Binds
    @Singleton
    fun bindUsageUseCase(impl: UsageUseCaseImpl): UsageUseCase

    @Binds
    @Singleton
    fun bindDataRefreshNotifier(impl: DataRefreshNotifierImpl): DataRefreshNotifier

    @Binds
    @Singleton
    fun bindDataChangeNotifier(impl: UpdateCoordinator): DataChangeNotifier

    @Binds
    @Singleton
    fun bindWidgetUpdateCoordinator(impl: UpdateCoordinator): WidgetUpdateCoordinator

    @Binds
    @Singleton
    fun bindNetworkStatsProvider(impl: RealNetworkStatsProvider): NetworkStatsProvider

    @Binds
    @IntoSet
    fun bindCallTrackerStatus(impl: CallTracker): TrackingStatusProvider

    @Binds
    @IntoSet
    fun bindTrafficTrackerStatus(impl: TrafficTracker): TrackingStatusProvider

    @Binds
    @IntoSet
    fun bindUpdateCoordinatorStatus(impl: UpdateCoordinator): TrackingStatusProvider

    @Binds
    @IntoSet
    fun bindSimIdentityObserverStatus(impl: SimIdentityObserver): TrackingStatusProvider

    @Binds
    @IntoSet
    fun bindUsageStateStatus(impl: UsageStateRepositoryImpl): TrackingStatusProvider
}
