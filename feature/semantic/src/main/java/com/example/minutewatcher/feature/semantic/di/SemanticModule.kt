package com.example.minutewatcher.feature.semantic.di

import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.feature.semantic.ShortcutApplier
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.SystemShortcutApplier
import com.example.minutewatcher.feature.semantic.search.AppSearchWrapper
import com.example.minutewatcher.feature.semantic.search.AppSearchWrapperImpl
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
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
    fun bindAppFunctionAvailability(impl: com.example.minutewatcher.feature.semantic.appfunctions.AppFunctionAvailabilityImpl): com.example.minutewatcher.core.domain.AppFunctionAvailability
}
