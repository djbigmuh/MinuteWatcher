package com.example.minutewatcher.core.android.testing

import com.example.minutewatcher.core.android.util.OperatorResolver
import com.example.minutewatcher.core.android.util.SimCardMapper
import com.example.minutewatcher.core.common.util.AppLogger
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit rule that resets global singletons before and after each test.
 * This is crucial for maintaining test isolation when running multiple tests in the same JVM.
 */
class SingletonResetRule : TestWatcher() {
    override fun starting(description: Description) {
        resetAll()
    }

    override fun finished(description: Description) {
        resetAll()
    }

    private fun resetAll() {
        AppLogger.resetForTest()
        SimCardMapper.resetForTest()
        OperatorResolver.resetForTest()
    }
}
