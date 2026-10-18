package host.serv.djbigmuh.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.hours

class CallDurationConstantsTest {
    @Test
    fun `max call duration constant equals twelve hours in milliseconds`() {
        assertEquals(
            12.hours.inWholeMilliseconds,
            Constants.Calls.MAX_CALL_DURATION_MS,
        )
    }
}
