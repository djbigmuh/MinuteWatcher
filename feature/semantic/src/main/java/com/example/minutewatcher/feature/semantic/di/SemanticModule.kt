package com.example.minutewatcher.feature.semantic.di

import android.content.Context
import com.example.minutewatcher.core.data.di.ApplicationScope
import com.example.minutewatcher.core.domain.UsageStateRepository
import kotlinx.coroutines.CoroutineScope
import com.example.minutewatcher.feature.semantic.SemanticRepository
import com.example.minutewatcher.feature.semantic.ShortcutManager
import com.example.minutewatcher.feature.semantic.search.SemanticSearchManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
object SemanticModule {

    @Provides
    @Singleton
    fun provideSemanticSearchManager(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope
    ): SemanticSearchManager {
        return SemanticSearchManager(context, scope)
    }

    @Provides
    @Singleton
    fun provideShortcutManager(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope
    ): ShortcutManager {
        return ShortcutManager(context, scope)
    }

    @Provides
    @Singleton
    fun provideSemanticRepository(
        usageStateRepository: UsageStateRepository,
    ): SemanticRepository {
        return SemanticRepository(usageStateRepository)
    }
}
