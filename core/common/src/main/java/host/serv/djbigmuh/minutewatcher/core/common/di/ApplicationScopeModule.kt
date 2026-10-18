package host.serv.djbigmuh.minutewatcher.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import host.serv.djbigmuh.minutewatcher.core.common.util.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

internal fun applicationScopeExceptionHandler(): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        AppLogger.e("ApplicationScope uncaught exception", throwable)
    }

/**
 * Provides the application-lifetime [CoroutineScope].
 *
 * The scope uses a [SupervisorJob] so a failure of one child never cancels
 * siblings, and the default dispatcher supplied via [DefaultDispatcher].
 * Contains a last-line handler to log uncaught exceptions of child coroutines.
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
    ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher + applicationScopeExceptionHandler())
}
