package com.example.minutewatcher.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Provides the application-lifetime [CoroutineScope].
 *
 * The scope uses a [SupervisorJob] so a failure of one child never cancels
 * siblings, and the default dispatcher supplied via [DefaultDispatcher].
 * It lives as long as the process; components must not cancel it.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApplicationScopeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)
}
