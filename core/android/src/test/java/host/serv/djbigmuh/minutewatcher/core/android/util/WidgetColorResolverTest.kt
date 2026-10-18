package host.serv.djbigmuh.minutewatcher.core.android.util

import android.graphics.Color
import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.util.UsageColors
import host.serv.djbigmuh.minutewatcher.core.model.util.UsageStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetColorResolverTest {
    private val threshold = Constants.UI.LUMINANCE_THRESHOLD.toDouble()

    @Test
    fun `resolveTextColors returns black and dkgray when luminance is above threshold`() {
        val colors = WidgetColorResolver.resolveTextColors(threshold + 0.1)
        assertEquals(Color.BLACK to Color.DKGRAY, colors)
    }

    @Test
    fun `resolveNoDataColor returns black when background color luminance is above threshold`() {
        val color = WidgetColorResolver.resolveNoDataColor(Color.WHITE)
        assertEquals(Color.BLACK, color)
    }

    @Test
    fun `getStatusColor returns light background palette when luminance is above threshold`() {
        for (status in UsageStatus.entries) {
            val color = WidgetColorResolver.getStatusColor(status, threshold + 0.1)
            assertEquals(UsageColors.get(status, isDarkBackground = false), color)
        }
    }

    @Test
    fun `resolveTextColors returns white and ltgray when luminance is below threshold`() {
        val colors = WidgetColorResolver.resolveTextColors(threshold - 0.1)
        assertEquals(Color.WHITE to Color.LTGRAY, colors)
    }

    @Test
    fun `resolveNoDataColor returns white when background color luminance is below threshold`() {
        val color = WidgetColorResolver.resolveNoDataColor(Color.BLACK)
        assertEquals(Color.WHITE, color)
    }

    @Test
    fun `getStatusColor returns dark background palette when luminance is below threshold`() {
        for (status in UsageStatus.entries) {
            val color = WidgetColorResolver.getStatusColor(status, threshold - 0.1)
            assertEquals(UsageColors.get(status, isDarkBackground = true), color)
        }
    }

    @Test
    fun `resolveTextColors returns white and ltgray when luminance equals threshold`() {
        val colors = WidgetColorResolver.resolveTextColors(threshold)
        assertEquals(Color.WHITE to Color.LTGRAY, colors)
    }

    @Test
    fun `getStatusColor returns dark background palette when luminance equals threshold`() {
        for (status in UsageStatus.entries) {
            val color = WidgetColorResolver.getStatusColor(status, threshold)
            assertEquals(UsageColors.get(status, isDarkBackground = true), color)
        }
    }
}
