package host.serv.djbigmuh.minutewatcher.feature.semantic.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import host.serv.djbigmuh.minutewatcher.core.domain.AppFunctionAvailability
import host.serv.djbigmuh.minutewatcher.core.domain.TrackingStatusProvider
import host.serv.djbigmuh.minutewatcher.feature.semantic.ShortcutApplier
import host.serv.djbigmuh.minutewatcher.feature.semantic.ShortcutManager
import host.serv.djbigmuh.minutewatcher.feature.semantic.SystemShortcutApplier
import host.serv.djbigmuh.minutewatcher.feature.semantic.appfunctions.AppFunctionAvailabilityImpl
import host.serv.djbigmuh.minutewatcher.feature.semantic.appfunctions.AppFunctionStateGateway
import host.serv.djbigmuh.minutewatcher.feature.semantic.appfunctions.PlatformAppFunctionStateGateway
import host.serv.djbigmuh.minutewatcher.feature.semantic.search.AppSearchWrapper
import host.serv.djbigmuh.minutewatcher.feature.semantic.search.AppSearchWrapperImpl
import host.serv.djbigmuh.minutewatcher.feature.semantic.search.SemanticSearchManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface SemanticModule {
    @Binds
    @Singleton
    @IntoSet
    fun bindSemanticSearchManagerStatus(impl: SemanticSearchManager): TrackingStatusProvider

    @Binds
    @Singleton
    @IntoSet
    fun bindShortcutManagerStatus(impl: ShortcutManager): TrackingStatusProvider

    @Binds
    @Singleton
    fun bindShortcutApplier(impl: SystemShortcutApplier): ShortcutApplier

    @Binds
    @Singleton
    fun bindAppSearchWrapper(impl: AppSearchWrapperImpl): AppSearchWrapper

    @Binds
    @Singleton
    fun bindAppFunctionAvailability(impl: AppFunctionAvailabilityImpl): AppFunctionAvailability

    @Binds
    @Singleton
    fun bindAppFunctionStateGateway(impl: PlatformAppFunctionStateGateway): AppFunctionStateGateway
}
