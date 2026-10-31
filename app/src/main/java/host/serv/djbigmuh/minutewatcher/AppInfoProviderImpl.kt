package host.serv.djbigmuh.minutewatcher

import host.serv.djbigmuh.minutewatcher.core.domain.AppInfoProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [AppInfoProvider] that reads version metadata from application `BuildConfig`.
 *
 * Note: The constant return values of properties in this implementation are a consequence of the
 * production binding to compiled build config fields; substitutability and testability are fully
 * guaranteed by the [AppInfoProvider] interface abstraction.
 */
@Singleton
class AppInfoProviderImpl
    @Inject
    constructor() : AppInfoProvider {
        override val versionName: String
            get() = BuildConfig.VERSION_NAME

        override val code: Int
            get() = BuildConfig.VERSION_CODE
    }
