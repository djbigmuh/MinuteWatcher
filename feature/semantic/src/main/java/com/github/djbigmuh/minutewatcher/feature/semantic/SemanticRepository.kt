package com.github.djbigmuh.minutewatcher.feature.semantic

import com.github.djbigmuh.minutewatcher.core.domain.UsageStateRepository
import com.github.djbigmuh.minutewatcher.core.model.GlobalSnapshot
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
