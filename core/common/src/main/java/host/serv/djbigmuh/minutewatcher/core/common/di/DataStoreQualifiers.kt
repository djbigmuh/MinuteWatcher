package host.serv.djbigmuh.minutewatcher.core.common.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SimDataStore
