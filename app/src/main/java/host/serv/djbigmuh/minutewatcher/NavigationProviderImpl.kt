package host.serv.djbigmuh.minutewatcher

import host.serv.djbigmuh.minutewatcher.core.domain.NavigationProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationProviderImpl
@Inject
constructor() : NavigationProvider {
    override val mainActivityClassName: String = MainActivity::class.java.name
}
