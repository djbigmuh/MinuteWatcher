package com.example.minutewatcher.feature.semantic.di

import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.feature.semantic.ShortcutApplier
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.SystemShortcutApplier
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
// @Suppress("unused") is required because Hilt discovers bindings via reflection.
@Suppress("unused")
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
    fun bindAppSearchWrapper(
        impl: com.example.minutewatcher.feature.semantic.search.AppSearchWrapperImpl,
    ): com.example.minutewatcher.feature.semantic.search.AppSearchWrapper
}
