package host.serv.djbigmuh.minutewatcher.core.data

import host.serv.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import host.serv.djbigmuh.minutewatcher.core.model.Constants.SimConstants
import host.serv.djbigmuh.minutewatcher.core.model.IdentitySource
import host.serv.djbigmuh.minutewatcher.core.model.SimIdentity
import javax.inject.Inject

/**
 * Benchmark implementation of [MockSimProvider] for performance and load testing variants.
 *
 * Generates the requested number of simulated SIM cards, clamped to the maximum supported slot count.
 *
 * Hilt entry point of the mockBenchmark build variant: instantiated via dependency injection,
 * never referenced directly from code.
 */
class BenchmarkMockSimProvider
    @Inject
    constructor() : MockSimProvider {
        override fun getMockSims(benchmarkSimCount: Int): List<ActiveSimInfo>? {
            if (benchmarkSimCount <= 0) return null

            val safeCount = benchmarkSimCount.coerceIn(0, SimConstants.MAX_SUPPORTED_SIM_SLOTS)
            return (0 until safeCount).map { i ->
                val identity =
                    SimIdentity(
                        // Prefix "mock_" is used to avoid collision with legacy DataStore migration keys
                        // that often use "_sim_<slot>" suffixes.
                        stableId = "mock_$i",
                        subscriptionId = MOCK_SUB_ID_BASE + i,
                        slotIndex = i,
                        source = IdentitySource.MOCK,
                        isStable = true,
                    )

                ActiveSimInfo(
                    identity = identity,
                    slotIndex = i,
                    subscriptionId = identity.subscriptionId ?: (MOCK_SUB_ID_BASE + i),
                    iccId = "mock_icc_$i",
                    displayName = "SIM ${i + 1}",
                    carrierName = "Mock Carrier ${i + 1}",
                    isDefaultData = i == 0,
                    isDefaultVoice = i == 0,
                )
            }
        }

        private companion object {
            private const val MOCK_SUB_ID_BASE = 1000
        }
    }
