package com.example.minutewatcher.core.data

import com.example.minutewatcher.core.model.ActiveSimInfo
import javax.inject.Inject

class NoOpMockSimProvider
    @Inject
    constructor() : MockSimProvider {
        override fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>? = null
    }
