package com.example.minutewatcher.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainImmediateDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @IoDispatcher
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun providesMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @MainImmediateDispatcher
    @Suppress("unused") // Used by Hilt but not directly referenced in code.
    fun providesMainImmediateDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}
