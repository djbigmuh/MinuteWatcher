package com.example.minutewatcher.feature.semantic

import com.example.minutewatcher.core.domain.UsageStateRepository
import com.example.minutewatcher.core.model.GlobalSnapshot
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SemanticRepository
    @Inject
    constructor(
        usageStateRepository: UsageStateRepository,
    ) {
        val snapshot: StateFlow<GlobalSnapshot> = usageStateRepository.observeSnapshot()
    }
