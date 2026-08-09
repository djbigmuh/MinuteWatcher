package com.example.minutewatcher.core.android.di

import com.example.minutewatcher.core.android.util.WidgetStringProvider
import com.example.minutewatcher.core.android.util.WidgetStringProviderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface AndroidUtilModule {
    @Binds
    @Singleton
    fun bindWidgetStringProvider(impl: WidgetStringProviderImpl): WidgetStringProvider
}
