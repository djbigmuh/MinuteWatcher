package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.domain.BenchmarkUseCase
import javax.inject.Inject

class NoOpBenchmarkUseCase
    @Inject
    constructor() : BenchmarkUseCase {
        override suspend fun populateMockData(simCount: Int) {
            // No-op for non-benchmark variants
        }
    }
