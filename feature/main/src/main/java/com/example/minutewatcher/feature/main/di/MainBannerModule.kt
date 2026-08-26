package com.example.minutewatcher.feature.main.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused") // Hilt discovers bindings via reflection
abstract class MainBannerModule
