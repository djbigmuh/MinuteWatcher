package host.serv.djbigmuh.minutewatcher.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import host.serv.djbigmuh.minutewatcher.core.data.CallTracker
import host.serv.djbigmuh.minutewatcher.core.data.DataRefreshNotifierImpl
import host.serv.djbigmuh.minutewatcher.core.data.NetworkStatsProvider
import host.serv.djbigmuh.minutewatcher.core.data.RealNetworkStatsProvider
import host.serv.djbigmuh.minutewatcher.core.data.ResetManager
import host.serv.djbigmuh.minutewatcher.core.data.SimIdentityObserver
import host.serv.djbigmuh.minutewatcher.core.data.SimLimitsRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.SimUsageRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.TrackingStatusRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.TrafficTracker
import host.serv.djbigmuh.minutewatcher.core.data.UpdateCoordinator
import host.serv.djbigmuh.minutewatcher.core.data.UsageStateRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.UsageUseCaseImpl
import host.serv.djbigmuh.minutewatcher.core.domain.AutoResetUseCase
import host.serv.djbigmuh.minutewatcher.core.domain.DataChangeNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimLimitsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageAdjustmentRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageResetRepository
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusProvider
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import host.serv.djbigmuh.minutewatcher.core.domain.UsageUseCase
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetUpdateCoordinator
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
