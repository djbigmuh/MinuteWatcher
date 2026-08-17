package com.example.minutewatcher.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.minutewatcher.core.common.di.ApplicationScope
import com.example.minutewatcher.core.common.di.DefaultDispatcher
import com.example.minutewatcher.core.data.AppSettingsRepositoryImpl
import com.example.minutewatcher.core.data.BenchmarkUseCaseImpl
import com.example.minutewatcher.core.data.PermissionRepositoryImpl
import com.example.minutewatcher.core.data.PermissionStateNotifierImpl
import com.example.minutewatcher.core.domain.AppSettingsRepository
import com.example.minutewatcher.core.domain.BenchmarkUseCase
import com.example.minutewatcher.core.domain.PermissionRepository
import com.example.minutewatcher.core.domain.PermissionStateNotifier
import com.example.minutewatcher.core.model.Constants
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Два раздельных DataStore-инстанса для разделения ответственности:
 *
 * **app_settings** (`appDataStore`):
 * - Общие настройки приложения (тема, язык, уведомления)
 * - Настройки виджета (layout, прозрачность, размер шрифта)
 * - Флаги миграции и onboarding
 * - Строгое соблюдение API: на AOSP DataStore отвечает < 100 мс.
 *
 * **sim_settings** (`simDataStore`):
 * - SIM-специфичные данные (лимиты, расход, корректировки)
 * - Ключи генерируются на основе identity SIM (icc_<hash>, sub_<id>, sim_<slot>)
 * - Разделение от app_settings предотвращает конфликты ключей
 *   и позволяет независимо мигрировать SIM-данные.
 *
 * Оба инстанса создаются через делегат `by preferencesDataStore`,
 * что гарантирует ленивую инициализацию и потокобезопасность.
 * На AOSP DataStore гарантирует атомарность edit-операций.
 * Если кастомная прошивка не обеспечивает атомарность —
 * это проблема кастомной прошивки.
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
            SharedPreferencesMigration(context, "secret_usage_prefs"),
        )
    },
)

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsDataModule {
    @Binds
    @Singleton
    abstract fun bindAppSettingsRepository(impl: AppSettingsRepositoryImpl): AppSettingsRepository

    @Binds
    @Singleton
    abstract fun bindPermissionStateNotifier(impl: PermissionStateNotifierImpl): PermissionStateNotifier

    @Binds
    @Singleton
    abstract fun bindPermissionRepository(impl: PermissionRepositoryImpl): PermissionRepository

    @Binds
    @Singleton
    abstract fun bindBenchmarkUseCase(impl: BenchmarkUseCaseImpl): BenchmarkUseCase

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
        @ApplicationScope
        fun provideApplicationScope(
            @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
        ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)
    }
}
