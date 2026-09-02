package com.example.minutewatcher.core.common.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CommonEntryPoint {
    @MainImmediateDispatcher
    fun mainImmediateDispatcher(): CoroutineDispatcher

    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher

    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}
