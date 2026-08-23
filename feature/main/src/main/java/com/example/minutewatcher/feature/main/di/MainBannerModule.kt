package com.example.minutewatcher.feature.main.di

import com.example.minutewatcher.core.domain.TrackingStatusProvider
import com.example.minutewatcher.feature.main.controller.MainBannerController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MainBannerModule {
    @Binds
    @IntoSet
    @Singleton
    abstract fun bindMainBannerControllerStatus(impl: MainBannerController): TrackingStatusProvider
}
