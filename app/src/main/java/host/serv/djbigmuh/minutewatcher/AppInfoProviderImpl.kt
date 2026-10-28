package host.serv.djbigmuh.minutewatcher

import host.serv.djbigmuh.minutewatcher.core.domain.AppInfoProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInfoProviderImpl
    @Inject
    constructor() : AppInfoProvider {
        override val versionName: String
            get() = BuildConfig.VERSION_NAME

        override val code: Int
            get() = BuildConfig.VERSION_CODE
    }
