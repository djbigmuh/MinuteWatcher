package host.serv.djbigmuh.minutewatcher.core.android

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RobolectricSdkSmokeTest {
    @Test
    fun `verify Robolectric runs on expected SDK`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(application.applicationInfo.targetSdkVersion, Build.VERSION.SDK_INT)
    }
}
