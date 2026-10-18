package host.serv.djbigmuh.minutewatcher.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import host.serv.djbigmuh.minutewatcher.ActivityStarter
import host.serv.djbigmuh.minutewatcher.AppInfoProviderImpl
import host.serv.djbigmuh.minutewatcher.ContextActivityStarter
import host.serv.djbigmuh.minutewatcher.NavigationProviderImpl
import host.serv.djbigmuh.minutewatcher.core.domain.AppInfoProvider
import host.serv.djbigmuh.minutewatcher.core.domain.NavigationProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface NavigationModule {
    @Binds
    @Singleton
    fun bindNavigationProvider(impl: NavigationProviderImpl): NavigationProvider

    @Binds
    @Singleton
    fun bindActivityStarter(impl: ContextActivityStarter): ActivityStarter

    @Binds
    @Singleton
    fun bindAppInfoProvider(impl: AppInfoProviderImpl): AppInfoProvider
}
