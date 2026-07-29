package com.example.minutewatcher.core.data.di

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.minutewatcher.core.data.CallTracker
import com.example.minutewatcher.core.data.DataRefreshNotifierImpl
import com.example.minutewatcher.core.data.PermissionRepositoryImpl
import com.example.minutewatcher.core.data.PermissionStateNotifierImpl
import com.example.minutewatcher.core.data.ResetManager
import com.example.minutewatcher.core.data.SettingsRepositoryImpl
import com.example.minutewatcher.core.data.SimIdentitySeedRepositoryImpl
import com.example.minutewatcher.core.data.SimRepositoryImpl
import com.example.minutewatcher.core.data.TrafficTracker
import com.example.minutewatcher.core.data.UpdateCoordinator
import com.example.minutewatcher.core.data.UsageStateRepositoryImpl
import com.example.minutewatcher.core.data.WidgetSettingsRepository
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.DefaultDispatcher
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.AutoResetUseCase
import com.example.minutewatcher.core.domain.CallSessionRepository
import com.example.minutewatcher.core.domain.DataChangeNotifier
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.MigrationFlagsRepository
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.domain.SettingsRepository
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimIdentitySeedRepository
import com.example.minutewatcher.core.domain.SimInfoProvider
import com.example.minutewatcher.core.domain.SimLimitsRepository
import com.example.minutewatcher.core.domain.SimNotificationFlagsRepository
import com.example.minutewatcher.core.domain.SimRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
import com.example.minutewatcher.core.domain.SimWidgetVisibilityRepository
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.domain.WidgetSettingsLoader
import com.example.minutewatcher.core.model.Constants
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, Constants.PREFS_NAME))
    },
)

private val Context.simDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sim_settings",
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(context, "secret_usage_prefs"),
        )
    },
)

/**
 * app_settings: theme, language, widget general settings, migration flags, call session state.
 * sim_settings: per-SIM usage, limits, adjustments, widget visibility, notification flags.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused", "TooManyFunctions")
abstract class DataModule {
    @Binds
    @Singleton
    abstract fun bindSimIdentitySeedRepository(impl: SimIdentitySeedRepositoryImpl): SimIdentitySeedRepository

    @Binds
    @Singleton
    abstract fun bindSimRepository(impl: SimRepositoryImpl): SimRepository

    @Binds
    @Singleton
    abstract fun bindSimIdentityRepository(impl: SimRepositoryImpl): SimIdentityRepository

    @Binds
    @Singleton
    abstract fun bindSimUsageRepository(impl: SimRepositoryImpl): SimUsageRepository

    @Binds
    @Singleton
    abstract fun bindSimLimitsRepository(impl: SimRepositoryImpl): SimLimitsRepository

    @Binds
    @Singleton
    abstract fun bindSimWidgetVisibilityRepository(impl: SimRepositoryImpl): SimWidgetVisibilityRepository

    @Binds
    @Singleton
    abstract fun bindSimNotificationFlagsRepository(impl: SimRepositoryImpl): SimNotificationFlagsRepository

    @Binds
    @Singleton
    abstract fun bindSimInfoProvider(impl: SimRepositoryImpl): SimInfoProvider

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindAppSettingsRepository(impl: SettingsRepositoryImpl): AppSettingsRepository

    @Binds
    @Singleton
    abstract fun bindCallSessionRepository(impl: SettingsRepositoryImpl): CallSessionRepository

    @Binds
    @Singleton
    abstract fun bindMigrationFlagsRepository(impl: SettingsRepositoryImpl): MigrationFlagsRepository

    @Binds
    @Singleton
    abstract fun bindDataRefreshNotifier(impl: DataRefreshNotifierImpl): DataRefreshNotifier

    @Binds
    @Singleton
    abstract fun bindPermissionStateNotifier(impl: PermissionStateNotifierImpl): PermissionStateNotifier

    @Binds
    @Singleton
    abstract fun bindPermissionRepository(impl: PermissionRepositoryImpl): PermissionRepository

    @Binds
    @Singleton
    abstract fun bindUsageStateRepository(impl: UsageStateRepositoryImpl): UsageStateRepository

    @Binds
    @Singleton
    abstract fun bindAutoResetUseCase(impl: ResetManager): AutoResetUseCase

    @Binds
    @Singleton
    abstract fun bindDataChangeNotifier(impl: UpdateCoordinator): DataChangeNotifier

    @Binds
    @Singleton
    abstract fun bindWidgetSettingsLoader(impl: WidgetSettingsRepository): WidgetSettingsLoader

    @Binds
    @IntoSet
    abstract fun bindCallTrackerStatus(impl: CallTracker): TrackingStatusProvider

    @Binds
    @IntoSet
    abstract fun bindTrafficTrackerStatus(impl: TrafficTracker): TrackingStatusProvider

    companion object {
        @Provides
        @Singleton
        @AppDataStore
        fun provideAppDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = context.appDataStore

        @Provides
        @Singleton
        @SimDataStore
        fun provideSimDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = context.simDataStore

        @Provides
        @Singleton
        fun provideTelephonyManager(
            @ApplicationContext context: Context,
        ): TelephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        @Provides
        @Singleton
        fun provideSubscriptionManager(
            @ApplicationContext context: Context,
        ): SubscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(
            @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
        ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)
    }
}
