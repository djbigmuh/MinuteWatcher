package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo

/**
 * Interface for providing mock SIM card data across build variants.
 *
 * **Contract:**
 * - A return value of `null` indicates that no simulated SIM cards are provided for the current build variant,
 *   and calling code must fall back to physical device SIM cards.
 * - Different build variants supply specialized implementations selected via Hilt source set bindings.
 */
interface MockSimProvider {
    // Analysis runs per build variant. The debug and release variants compile only the no-op
    // implementation whose documented contract is to always return null without consuming the
    // requested count, while the benchmark variant compiles an implementation that consumes
    // benchmarkSimCount and returns generated cards. Within any single analyzed variant the
    // reports contradict the full contract, so they are suppressed at this declaration only.

    /**
     * Returns a list of simulated SIM cards, or `null` if simulation is disabled.
     *
     * @param benchmarkSimCount Number of simulated slots requested. A non-positive value indicates
     *                          no simulation, whereas a positive value is clamped to the maximum
     *                          supported slot count.
     */
    fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>?
}
