package host.serv.djbigmuh.minutewatcher.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import host.serv.djbigmuh.minutewatcher.core.data.SimWidgetVisibilityRepositoryImpl
import host.serv.djbigmuh.minutewatcher.core.data.WidgetSettingsRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimWidgetVisibilityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.WidgetSettingsLoader
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface WidgetSettingsDataModule {
    @Binds
    @Singleton
    fun bindSimWidgetVisibilityRepository(impl: SimWidgetVisibilityRepositoryImpl): SimWidgetVisibilityRepository

    @Binds
    @Singleton
    fun bindWidgetSettingsLoader(impl: WidgetSettingsRepository): WidgetSettingsLoader
}
