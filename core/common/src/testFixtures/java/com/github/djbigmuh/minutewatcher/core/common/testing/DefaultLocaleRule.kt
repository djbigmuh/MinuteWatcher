package com.github.djbigmuh.minutewatcher.core.common.testing

import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.Locale

/**
 * A JUnit [TestWatcher] that sets [Locale.setDefault] to [initialLocale]
 * for the duration of the test and restores the original locale afterward.
 */
class DefaultLocaleRule(
    val initialLocale: Locale = Locale.US,
) : TestWatcher() {
    private var originalLocale: Locale? = null

    override fun starting(description: Description) {
        originalLocale = Locale.getDefault()
        Locale.setDefault(initialLocale)
    }

    override fun finished(description: Description) {
        originalLocale?.let { Locale.setDefault(it) }
    }

    fun setLocale(locale: Locale) {
        Locale.setDefault(locale)
    }
}
