package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo

/**
 * Interface for providing mock SIM card data across build variants.
 *
 * **Contract:**
 * - A return value of `null` indicates that no simulated SIM cards are provided for the current build variant,
 *   and calling code must fall back to physical device SIM cards.
 * - Different build variants supply specialized implementations selected via Hilt source set bindings.
 */
interface MockSimProvider {
    /**
     * Returns a list of simulated SIM cards, or `null` if simulation is disabled.
     *
     * @param benchmarkSimCount Number of simulated slots requested. A non-positive value indicates
     *                          no simulation, whereas a positive value is clamped to the maximum
     *                          supported slot count.
     */
    fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>?
}
