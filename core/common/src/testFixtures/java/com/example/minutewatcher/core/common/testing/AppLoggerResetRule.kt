package com.example.minutewatcher.core.common.testing

import com.example.minutewatcher.core.common.util.AppLogger
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit rule that resets AppLogger before and after each test.
 */
class AppLoggerResetRule : TestWatcher() {
    override fun starting(description: Description) {
        AppLogger.resetForTest()
    }

    override fun finished(description: Description) {
        AppLogger.resetForTest()
    }
}
