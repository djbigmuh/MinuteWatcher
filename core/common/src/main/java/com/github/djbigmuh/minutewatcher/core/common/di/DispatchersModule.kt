package com.github.djbigmuh.minutewatcher.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainImmediateDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @IoDispatcher
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainImmediateDispatcher
    fun providesMainImmediateDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}

/**
 * Sanctioned fallback accessor for the IO dispatcher.
 *
 * Used by infrastructure that must run even when the Hilt entry point
 * is unavailable (e.g. receivers in non-Hilt test applications).
 * Direct `Dispatchers` references outside this file and `AppLogger`
 * are forbidden by the architecture policy.
 */
object IoDispatcherFallback {
    val io: CoroutineDispatcher
        get() = Dispatchers.IO
}
