package host.serv.djbigmuh.minutewatcher.core.common.testing

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * A JUnit [TestWatcher] that resets per-app locales via [AppCompatDelegate] before and after each test.
 */
class AppLocaleResetRule : TestWatcher() {
    override fun starting(description: Description) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    override fun finished(description: Description) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }
}
