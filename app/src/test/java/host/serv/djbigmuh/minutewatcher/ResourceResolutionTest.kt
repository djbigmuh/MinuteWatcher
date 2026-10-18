package host.serv.djbigmuh.minutewatcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import host.serv.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = MinuteWatcherTestApp::class)
class ResourceResolutionTest {
    @Test
    fun `ic_language and ic_palette resolve in core resources context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val languageDrawable = context.getDrawable(R.drawable.ic_language)
        assertNotNull("ic_language drawable resource must resolve", languageDrawable)

        val paletteDrawable = context.getDrawable(R.drawable.ic_palette)
        assertNotNull("ic_palette drawable resource must resolve", paletteDrawable)
    }
}
