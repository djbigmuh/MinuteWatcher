package com.github.djbigmuh.minutewatcher.core.common.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CommonEntryPoint {
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher
}
