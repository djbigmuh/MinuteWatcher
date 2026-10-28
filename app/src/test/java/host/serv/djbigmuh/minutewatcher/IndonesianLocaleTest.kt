package host.serv.djbigmuh.minutewatcher

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.android.util.AppLocaleContexts
import host.serv.djbigmuh.minutewatcher.core.resources.testing.LocaleResourceDirs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class IndonesianLocaleTest {
    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `indonesian language tag and resource dir mapping is correct`() {
        val locale = Locale.forLanguageTag("id")
        assertEquals("id", locale.language)
        assertEquals("values-in", LocaleResourceDirs.resourceDirForTag("id"))
        assertEquals("id", LocaleResourceDirs.languageTagOfQualifier("values-in"))
    }

    @Test
    fun `indonesian locale resolves resources from values-in`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("id"))
        val indonesianContext = AppLocaleContexts.forAppLocale(appContext)
        val indoString = indonesianContext.getString(host.serv.djbigmuh.minutewatcher.core.resources.R.string.app_name)
        assertNotEquals(null, indoString)
    }
}
