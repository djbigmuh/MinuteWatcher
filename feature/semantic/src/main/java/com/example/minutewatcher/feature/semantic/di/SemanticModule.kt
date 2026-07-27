package com.example.minutewatcher.feature.semantic.di

import android.content.Context
import com.example.minutewatcher.core.data.di.ApplicationScope
import com.example.minutewatcher.core.domain.NavigationProvider
import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.feature.semantic.SemanticRepository
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class SemanticModule {
    @Binds
    @Singleton
    @IntoSet
    abstract fun bindSemanticSearchManagerStatus(impl: SemanticSearchManager): TrackingStatusProvider

    companion object {
        @Provides
        @Singleton
        fun provideSemanticSearchManager(
            @ApplicationContext context: Context,
            @ApplicationScope scope: CoroutineScope,
        ): SemanticSearchManager = SemanticSearchManager(context, scope)

        @Provides
        @Singleton
        fun provideShortcutManager(
            @ApplicationContext context: Context,
            @ApplicationScope scope: CoroutineScope,
            navigationProvider: NavigationProvider,
        ): ShortcutManager = ShortcutManager(context, scope, navigationProvider)

        @Provides
        @Singleton
        fun provideSemanticRepository(usageStateRepository: UsageStateRepository): SemanticRepository =
            SemanticRepository(usageStateRepository)
    }
}
