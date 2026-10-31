package host.serv.djbigmuh.minutewatcher.core.data

import org.junit.Assert.assertNull
import org.junit.Test

class NoOpMockSimProviderTest {
    private val provider = NoOpMockSimProvider()

    @Test
    fun `getMockSims returns null for positive count`() {
        assertNull(provider.getMockSims(2))
    }

    @Test
    fun `getMockSims returns null for zero count`() {
        assertNull(provider.getMockSims(0))
    }

    @Test
    fun `getMockSims returns null for negative count`() {
        assertNull(provider.getMockSims(-1))
    }
}
