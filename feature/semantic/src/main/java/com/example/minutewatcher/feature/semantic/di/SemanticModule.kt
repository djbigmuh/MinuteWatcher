package com.example.minutewatcher.feature.semantic.di

import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
interface SemanticModule {
    @Binds
    @Singleton
    @IntoSet
    fun bindSemanticSearchManagerStatus(impl: SemanticSearchManager): TrackingStatusProvider

    @Binds
    @Singleton
    fun bindAppSearchWrapper(
        impl: com.example.minutewatcher.feature.semantic.search.AppSearchWrapperImpl,
    ): com.example.minutewatcher.feature.semantic.search.AppSearchWrapper
}
