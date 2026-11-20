package com.github.djbigmuh.minutewatcher.core.data.di

import com.github.djbigmuh.minutewatcher.core.data.CallSessionRepositoryImpl
import com.github.djbigmuh.minutewatcher.core.data.MigrationFlagsRepositoryImpl
import com.github.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import com.github.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface MigrationDataModule {
    @Binds
    @Singleton
    fun bindCallSessionRepository(impl: CallSessionRepositoryImpl): CallSessionRepository

    @Binds
    @Singleton
    fun bindMigrationFlagsRepository(impl: MigrationFlagsRepositoryImpl): MigrationFlagsRepository
}
