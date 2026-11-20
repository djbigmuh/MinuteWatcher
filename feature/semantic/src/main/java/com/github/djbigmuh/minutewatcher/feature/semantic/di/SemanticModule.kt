package com.github.djbigmuh.minutewatcher.feature.semantic.di

import com.github.djbigmuh.minutewatcher.core.domain.AppFunctionAvailability
import com.github.djbigmuh.minutewatcher.core.domain.TrackingStatusProvider
import com.github.djbigmuh.minutewatcher.feature.semantic.ShortcutApplier
import com.github.djbigmuh.minutewatcher.feature.semantic.ShortcutManager
import com.github.djbigmuh.minutewatcher.feature.semantic.SystemShortcutApplier
import com.github.djbigmuh.minutewatcher.feature.semantic.appfunctions.AppFunctionAvailabilityImpl
import com.github.djbigmuh.minutewatcher.feature.semantic.appfunctions.AppFunctionStateGateway
import com.github.djbigmuh.minutewatcher.feature.semantic.appfunctions.PlatformAppFunctionStateGateway
import com.github.djbigmuh.minutewatcher.feature.semantic.search.AppSearchWrapper
import com.github.djbigmuh.minutewatcher.feature.semantic.search.AppSearchWrapperImpl
import com.github.djbigmuh.minutewatcher.feature.semantic.search.SemanticSearchManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
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
