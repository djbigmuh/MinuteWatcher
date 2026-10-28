package host.serv.djbigmuh.minutewatcher.core.common.testing

import host.serv.djbigmuh.minutewatcher.core.common.util.resetAppLoggerForTest
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit rule that resets AppLogger before and after each test.
 */
class AppLoggerResetRule : TestWatcher() {
    override fun starting(description: Description) {
        resetAppLoggerForTest()
    }

    override fun finished(description: Description) {
        resetAppLoggerForTest()
    }
}
