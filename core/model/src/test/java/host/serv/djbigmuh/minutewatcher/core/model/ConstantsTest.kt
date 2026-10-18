package host.serv.djbigmuh.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConstantsTest {
    @Test
    fun `max traffic limit mb is consistent with gb and mb per gb`() {
        val expected = (Constants.MAX_TRAFFIC_LIMIT_GB * Constants.MB_PER_GB).toInt()
        assertEquals(expected, Constants.MAX_TRAFFIC_LIMIT_MB)
        assertEquals(10485760, Constants.MAX_TRAFFIC_LIMIT_MB)
    }
}
