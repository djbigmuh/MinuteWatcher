package host.serv.djbigmuh.minutewatcher.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import host.serv.djbigmuh.minutewatcher.core.data.MockSimProvider
import host.serv.djbigmuh.minutewatcher.core.data.NoOpMockSimProvider
import javax.inject.Singleton

// Hilt entry point of the noop build variant: instantiated via dependency injection,
// never referenced directly from code.
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
interface MockSimModule {
    @Binds
    @Singleton
    fun bindMockSimProvider(impl: NoOpMockSimProvider): MockSimProvider
}
