package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.domain.SimIdentityRepository
import com.example.minutewatcher.core.domain.SimInfoProvider
import com.example.minutewatcher.core.model.ActiveSimInfo
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimInfoProviderImpl
    @Inject
    constructor(
        private val simIdentityRepository: SimIdentityRepository,
    ) : SimInfoProvider {
        override fun observeActiveSims(): Flow<List<ActiveSimInfo>> = simIdentityRepository.observeActiveSims()
    }
