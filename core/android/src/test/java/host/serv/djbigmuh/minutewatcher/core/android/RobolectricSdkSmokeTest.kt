package host.serv.djbigmuh.minutewatcher.core.android

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RobolectricSdkSmokeTest {
    @Test
    fun `verify Robolectric runs on expected SDK`() {
        val expectedSdk = System.getProperty("robolectric.sdk")?.toIntOrNull() ?: 37
        assertEquals(expectedSdk, Build.VERSION.SDK_INT)
    }
}
