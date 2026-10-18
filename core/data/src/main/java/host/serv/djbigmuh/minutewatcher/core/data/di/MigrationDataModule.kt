package host.serv.djbigmuh.minutewatcher.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import host.serv.djbigmuh.minutewatcher.core.data.CallSessionRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.MigrationFlagsRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.domain.CallSessionRepository
import host.serv.djbigmuh.minutewatcher.core.domain.MigrationFlagsRepository
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
