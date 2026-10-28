package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import javax.inject.Inject

class NoOpMockSimProvider
    @Inject
    constructor() : MockSimProvider {
        override fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>? = null
    }
