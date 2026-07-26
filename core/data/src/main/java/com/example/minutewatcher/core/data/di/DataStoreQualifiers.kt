package com.example.minutewatcher.core.data.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SimDataStore
