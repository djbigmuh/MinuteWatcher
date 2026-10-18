package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo

/**
 * Interface for providing mock SIM data.
 * The production implementation should return null to ensure no mock data is used.
 */
interface MockSimProvider {
    fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>?
}
