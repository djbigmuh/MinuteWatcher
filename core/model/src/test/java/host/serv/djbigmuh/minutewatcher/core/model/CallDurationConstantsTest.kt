package host.serv.djbigmuh.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.hours

class CallDurationConstantsTest {
    @Test
    fun `max call duration constant equals twelve hours in milliseconds`() {
        val expectedMillis = 12.hours.inWholeMilliseconds
        val actualMillis = Constants.Calls.MAX_CALL_DURATION_MS
        assertEquals(expectedMillis, actualMillis)
    }
}
