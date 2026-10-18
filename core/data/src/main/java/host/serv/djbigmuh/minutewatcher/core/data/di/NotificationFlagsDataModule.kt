package host.serv.djbigmuh.minutewatcher.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import host.serv.djbigmuh.minutewatcher.core.data.SimNotificationFlagsRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.domain.SimNotificationFlagsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface NotificationFlagsDataModule {
    @Binds
    @Singleton
    fun bindSimNotificationFlagsRepository(impl: SimNotificationFlagsRepositoryImpl): SimNotificationFlagsRepository
}
