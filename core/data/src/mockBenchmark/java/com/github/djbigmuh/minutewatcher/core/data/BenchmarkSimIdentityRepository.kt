package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.di.ApplicationScope
import com.github.djbigmuh.minutewatcher.core.domain.AppSettingsRepository
import com.github.djbigmuh.minutewatcher.core.domain.SimIdentityRepository
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Benchmark implementation / decorator of [SimIdentityRepository] for performance and load testing variants.
 * Prioritizes mock SIMs provided by [MockSimProvider] when `benchmarkSimCount` > 0.
 */
@Singleton
class BenchmarkSimIdentityRepository
    @Inject
    constructor(
        private val delegate: SimIdentityRepositoryImpl,
        private val mockSimProvider: MockSimProvider,
        private val appSettingsRepository: AppSettingsRepository,
        @ApplicationScope private val scope: CoroutineScope,
    ) : SimIdentityRepository by delegate {
        private val resolvedBenchmarkActiveSimsCache: StateFlow<List<ActiveSimInfo>?> =
            combine(
                delegate.observeResolvedActiveSims(),
                observeBenchmarkSimCount(),
            ) { physical, benchmarkSimCount ->
                val mocks = mockSimProvider.getMockSims(benchmarkSimCount)
                mocks ?: physical
            }.distinctUntilChanged()
                .stateIn(
                    scope,
                    SharingStarted.Eagerly,
                    null,
                )

        override fun observeResolvedActiveSims(): StateFlow<List<ActiveSimInfo>?> = resolvedBenchmarkActiveSimsCache

        private fun observeBenchmarkSimCount(): Flow<Int> =
            appSettingsRepository
                .observeAppSettings()
                .map { it.benchmarkSimCount }
                .distinctUntilChanged()
    }
