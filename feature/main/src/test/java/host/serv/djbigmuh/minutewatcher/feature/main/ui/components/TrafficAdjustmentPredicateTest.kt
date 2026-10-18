package host.serv.djbigmuh.minutewatcher.feature.main.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TrafficAdjustmentPredicateTest {
    @Test
    fun `zero adjustment returns false`() {
        assertFalse(shouldShowTrafficAdjustmentLabel(0f, Locale.US))
        assertFalse(shouldShowTrafficAdjustmentLabel(0f, Locale.GERMANY))
    }

    @Test
    fun `small non-zero adjustment formatting to zero is hidden`() {
        assertFalse(shouldShowTrafficAdjustmentLabel(0.04f, Locale.US))
        assertFalse(shouldShowTrafficAdjustmentLabel(-0.04f, Locale.US))
        assertFalse(shouldShowTrafficAdjustmentLabel(-0.001f, Locale.GERMANY))
        assertFalse(shouldShowTrafficAdjustmentLabel(0.04f, Locale.GERMANY))
    }

    @Test
    fun `arabic locale zero and non-zero formatting`() {
        val arabicLocale = Locale.forLanguageTag("ar")
        assertFalse(shouldShowTrafficAdjustmentLabel(0.04f, arabicLocale))
        assertTrue(shouldShowTrafficAdjustmentLabel(0.1f, arabicLocale))
    }

    @Test
    fun `significant adjustment is shown`() {
        assertTrue(shouldShowTrafficAdjustmentLabel(0.1f, Locale.US))
        assertTrue(shouldShowTrafficAdjustmentLabel(-0.1f, Locale.GERMANY))
        assertTrue(shouldShowTrafficAdjustmentLabel(12.34f, Locale.US))
    }
}
