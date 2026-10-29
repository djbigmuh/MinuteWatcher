package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import javax.inject.Inject

// Hilt entry point of the noop build variant: instantiated via dependency injection,
// never referenced directly from code.
@Suppress("unused")
class NoOpMockSimProvider
    @Inject
    constructor() : MockSimProvider {
        // The parameter belongs to the MockSimProvider contract shared with the benchmark
        // variant; the production no-op intentionally ignores it and returns null.
        @Suppress("UNUSED_PARAMETER")
        override fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>? = null
    }
