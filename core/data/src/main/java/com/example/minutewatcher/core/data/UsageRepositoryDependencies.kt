package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.common.util.TimeProvider
import com.example.minutewatcher.core.data.semantic.SemanticCapabilities
import com.example.minutewatcher.core.data.semantic.SemanticVersion
import com.example.minutewatcher.core.domain.DataRefreshNotifier
import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimUsageRepository
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
