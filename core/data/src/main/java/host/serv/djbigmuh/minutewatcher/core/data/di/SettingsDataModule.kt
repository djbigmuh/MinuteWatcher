package host.serv.djbigmuh.minutewatcher.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import host.serv.djbigmuh.minutewatcher.core.common.di.AppDataStore
import host.serv.djbigmuh.minutewatcher.core.common.di.SimDataStore
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
import javax.inject.Singleton

/**
 * Two separate DataStore instances for separation of concerns:
 *
 * **app_settings** (`appDataStore`):
 * - General application settings (theme, language, notifications)
 * - Widget settings (layout, transparency, font size)
 * - Migration and onboarding flags
 * - Strict API compliance: on AOSP DataStore responds in < 100 ms.
 *
 * **sim_settings** (`simDataStore`):
 * - SIM-specific data (limits, usage, adjustments)
 * - Keys generated based on SIM identity (icc_<hash>, sub_<id>, sim_<slot>)
 * - Separation from app_settings prevents key collisions
 *   and allows independent migration of SIM data.
 *
 * Both instances are created via `by preferencesDataStore` delegate,
 * guaranteeing lazy initialization and thread safety.
 * On AOSP DataStore guarantees atomicity of edit operations.
 * If custom firmware does not guarantee atomicity —
 * this is a custom firmware issue.
 */
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
            SharedPreferencesMigration(context, Constants.LEGACY_SIM_PREFS_NAME),
        )
    },
)

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
        ): DataStore<Preferences> = context.appDataStore

        @Provides
        @Singleton
        @SimDataStore
        fun provideSimDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = context.simDataStore
    }
}
