package host.serv.djbigmuh.minutewatcher.core.common.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import host.serv.djbigmuh.minutewatcher.core.common.util.SystemTimeProvider
import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    @Provides
    @Singleton
    fun providesTimeProvider(): TimeProvider = SystemTimeProvider()
}
