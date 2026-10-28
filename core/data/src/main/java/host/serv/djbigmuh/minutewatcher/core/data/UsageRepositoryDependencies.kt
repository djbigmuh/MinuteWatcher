package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.common.util.TimeProvider
import host.serv.djbigmuh.minutewatcher.core.data.semantic.SemanticCapabilities
import host.serv.djbigmuh.minutewatcher.core.data.semantic.SemanticVersion
import host.serv.djbigmuh.minutewatcher.core.domain.DataRefreshNotifier
import host.serv.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import host.serv.djbigmuh.minutewatcher.core.domain.SimUsageRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Grouping of dependencies for UsageStateRepositoryImpl.
 * Avoids LongParameterList and simplifies the repository constructor.
 */
@Singleton
class UsageRepositoryDependencies
    @Inject
    constructor(
        val simIdentityRepository: SimIdentityRepository,
        val simUsageRepository: SimUsageRepository,
        val semanticCapabilities: SemanticCapabilities,
        val semanticVersion: SemanticVersion,
        val dataRefreshNotifier: DataRefreshNotifier,
        val timeProvider: TimeProvider,
    )
