package com.github.djbigmuh.minutewatcher.core.android.testing

import com.github.djbigmuh.minutewatcher.core.android.util.OperatorResolver
import com.github.djbigmuh.minutewatcher.core.android.util.SimCardMapper
import com.github.djbigmuh.minutewatcher.core.common.util.resetAppLoggerForTest
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
        resetAppLoggerForTest()
        SimCardMapper.reset()
        OperatorResolver.reset()
    }
}
