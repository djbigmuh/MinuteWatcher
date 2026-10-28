package host.serv.djbigmuh.minutewatcher.core.domain

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Use case for benchmarking and performance testing.
 *
 * All methods are safe to call from any coroutine context.
 * Implementation must comply with AOSP DataStore best practices.
 */
interface BenchmarkUseCase {
    /**
     * Fills the local storage with mock data for benchmarking purposes.
     *
     * @param simCount Number of mock SIM cards to create (1 or 2).
     * @throws IOException if storage access fails.
     * @throws CancellationException if the coroutine is canceled.
     */
    suspend fun populateMockData(simCount: Int)
}
