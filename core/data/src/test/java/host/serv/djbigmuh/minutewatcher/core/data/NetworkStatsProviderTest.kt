package host.serv.djbigmuh.minutewatcher.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkStatsProviderTest {
    @Test
    fun `parseNetworkStatsBytes returns Success with sum for zero bytes`() {
        val result = parseNetworkStatsBytes(0L, 0L)
        assertEquals(StatsResult.Success(0L), result)
    }

    @Test
    fun `parseNetworkStatsBytes returns Success with sum for positive bytes`() {
        val result = parseNetworkStatsBytes(100L, 200L)
        assertEquals(StatsResult.Success(300L), result)
    }

    @Test
    fun `parseNetworkStatsBytes returns Unavailable for negative rx and tx bytes`() {
        val result = parseNetworkStatsBytes(-1L, -1L)
        assertEquals(StatsResult.Unavailable, result)
    }

    @Test
    fun `parseNetworkStatsBytes returns Unavailable for negative rx bytes`() {
        val result = parseNetworkStatsBytes(-1L, 500L)
        assertEquals(StatsResult.Unavailable, result)
    }

    @Test
    fun `parseNetworkStatsBytes returns Unavailable for negative tx bytes`() {
        val result = parseNetworkStatsBytes(500L, -1L)
        assertEquals(StatsResult.Unavailable, result)
    }
}
