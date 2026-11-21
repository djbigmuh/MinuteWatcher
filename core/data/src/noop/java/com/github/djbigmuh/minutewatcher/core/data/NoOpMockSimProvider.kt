package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import javax.inject.Inject

/**
 * No-op implementation of [MockSimProvider] for production build variants.
 *
 * The production build variant without simulation does not provide test SIM cards
 * regardless of the requested count, as simulation is disabled in production.
 *
 * Hilt entry point of the noop build variant: instantiated via dependency injection,
 * never referenced directly from code.
 */
class NoOpMockSimProvider
    @Inject
    constructor() : MockSimProvider {
        /**
         * Returns `null` as this is the no-op implementation for production build variants where
         * simulation is disabled by design (benchmark variant supplies dynamic simulation data).
         */
        @Suppress("SameReturnValue")
        override fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>? = null
    }
