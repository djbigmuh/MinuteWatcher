package com.github.djbigmuh.minutewatcher.core.data.di

import com.github.djbigmuh.minutewatcher.core.data.SimIdentityRepositoryImpl
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface ProductionRepositoryModule {
    @Binds
    @Singleton
    fun bindSimIdentityRepository(impl: SimIdentityRepositoryImpl): SimIdentityRepository
}
