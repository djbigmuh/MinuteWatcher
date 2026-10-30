package host.serv.djbigmuh.minutewatcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkManagerOnDemandInitializationTest {
    @Test
    fun `MinuteWatcherApp implements Configuration Provider`() {
        val app = ApplicationProvider.getApplicationContext<Context>() as? Configuration.Provider
        assertNotNull("MinuteWatcherApp must implement Configuration.Provider", app)
        assertNotNull("workManagerConfiguration must not be null", app?.workManagerConfiguration)
    }
}
