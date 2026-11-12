package host.serv.djbigmuh.minutewatcher.core.common.testing

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.TimeZone

/**
 * A JUnit [TestWatcher] that sets [TimeZone.setDefault] to [initialTimeZone]
 * for the duration of the test and restores the original time zone afterward.
 */
class DefaultTimeZoneRule(
    val initialTimeZone: TimeZone = TimeZone.getTimeZone("UTC"),
) : TestWatcher() {
    private var originalTimeZone: TimeZone? = null

    override fun starting(description: Description) {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(initialTimeZone)
    }

    override fun finished(description: Description) {
        originalTimeZone?.let { TimeZone.setDefault(it) }
    }

    fun setTimeZone(timeZone: TimeZone) {
        TimeZone.setDefault(timeZone)
    }
}
