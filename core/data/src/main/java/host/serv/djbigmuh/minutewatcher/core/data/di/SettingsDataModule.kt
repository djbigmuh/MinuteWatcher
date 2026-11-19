package host.serv.djbigmuh.minutewatcher.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import host.serv.djbigmuh.minutewatcher.core.common.di.AppDataStore
import host.serv.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import host.serv.djbigmuh.minutewatcher.core.common.di.IoDispatcher
import host.serv.djbigmuh.minutewatcher.core.common.di.SimDataStore
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import host.serv.djbigmuh.minutewatcher.core.data.AodSummaryAvailabilityImpl
import host.serv.djbigmuh.minutewatcher.core.data.AppSettingsRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.AutoResetScheduleRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.BenchmarkUseCaseImpl
import host.serv.djbigmuh.minutewatcher.core.data.PermissionRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.PermissionStateNotifierImpl
import host.serv.djbigmuh.minutewatcher.core.domain.AodSummaryAvailability
import host.serv.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.AutoResetScheduleRepository
import host.serv.djbigmuh.minutewatcher.core.domain.BenchmarkUseCase
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.PermissionStateNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusProvider
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Two separate DataStore instances for separation of concerns:
 *
 * **app_settings** (`appDataStore`):
 * - General application settings (theme, language, notifications)
 * - Widget settings (layout, transparency, font size)
 * - Migration and onboarding flags
 *
 * **sim_settings** (`simDataStore`):
 * - SIM-specific data (limits, usage, adjustments)
 * - Keys generated based on SIM identity (icc_<hash>, sub_<id>, sim_<slot>)
 * - Separation from app_settings prevents key collisions
 *   and allows independent migration of SIM data.
 *
 * Both instances are created via singleton Dagger provider methods using
 * [PreferenceDataStoreFactory.create] with explicit injected [ApplicationScope],
 * [IoDispatcher], [CoroutineExceptionHandler], and [ReplaceFileCorruptionHandler].
 */
@Module
@InstallIn(SingletonComponent::class)
interface SettingsDataModule {
    /** Binds the concrete [AppSettingsRepositoryImpl] to the [AppSettingsRepository] interface.
     *  Used by Hilt to inject settings repository across the application. */
    @Binds
    @Singleton
    fun bindAppSettingsRepository(impl: AppSettingsRepositoryImpl): AppSettingsRepository

    @Binds
    @IntoSet
    fun bindAppSettingsStatus(impl: AppSettingsRepositoryImpl): TrackingStatusProvider

    /** Binds the concrete [PermissionStateNotifierImpl] to the [PermissionStateNotifier] interface.
     *  Used by Hilt to manage and notify about application permission states. */
    @Binds
    @Singleton
    fun bindPermissionStateNotifier(impl: PermissionStateNotifierImpl): PermissionStateNotifier

    /** Binds the concrete [PermissionRepositoryImpl] to the [PermissionRepository] interface.
     *  Used by Hilt to handle permission-related data and logic. */
    @Binds
    @Singleton
    fun bindPermissionRepository(impl: PermissionRepositoryImpl): PermissionRepository

    /** Binds the concrete [BenchmarkUseCaseImpl] to the [BenchmarkUseCase] interface.
     *  Used by Hilt to provide benchmarking functionality. */
    @Binds
    @Singleton
    fun bindBenchmarkUseCase(impl: BenchmarkUseCaseImpl): BenchmarkUseCase

    @Binds
    @Singleton
    fun bindAutoResetScheduleRepository(impl: AutoResetScheduleRepositoryImpl): AutoResetScheduleRepository

    @Binds
    @Singleton
    fun bindAodSummaryAvailability(impl: AodSummaryAvailabilityImpl): AodSummaryAvailability

    companion object {
        @Provides
        @Singleton
        @AppDataStore
        fun provideAppDataStore(
            @ApplicationContext context: Context,
            @ApplicationScope applicationScope: CoroutineScope,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler =
                    ReplaceFileCorruptionHandler { exception ->
                        AppLogger.e("AppDataStore file corrupted, resetting to empty preferences", exception)
                        emptyPreferences()
                    },
                migrations = listOf(SharedPreferencesMigration(context, Constants.PREFS_NAME)),
                scope =
                    CoroutineScope(
                        applicationScope.coroutineContext + ioDispatcher +
                            CoroutineExceptionHandler { _, throwable ->
                                AppLogger.e("AppDataStore scope uncaught exception", throwable)
                            },
                    ),
                produceFile = { context.preferencesDataStoreFile("app_settings") },
            )

        @Provides
        @Singleton
        @SimDataStore
        fun provideSimDataStore(
            @ApplicationContext context: Context,
            @ApplicationScope applicationScope: CoroutineScope,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler =
                    ReplaceFileCorruptionHandler { exception ->
                        AppLogger.e("SimDataStore file corrupted, resetting to empty preferences", exception)
                        emptyPreferences()
                    },
                migrations = listOf(SharedPreferencesMigration(context, Constants.LEGACY_SIM_PREFS_NAME)),
                scope =
                    CoroutineScope(
                        applicationScope.coroutineContext + ioDispatcher +
                            CoroutineExceptionHandler { _, throwable ->
                                AppLogger.e("SimDataStore scope uncaught exception", throwable)
                            },
                    ),
                produceFile = { context.preferencesDataStoreFile("sim_settings") },
            )
    }
}
