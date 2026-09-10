import android.content.Context
import com.example.minutewatcher.core.common.util.FormatUtils
import com.example.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Locale

class FormatUtilsTest {
    @Test
    fun testFormatTrafficGb() {
        // Result should be consistent regardless of environment because Locale.US is used internally.
        // No need to set Locale.setDefault(Locale.US) here.
        assertEquals("1.5", FormatUtils.formatTrafficGb(1.5f))
        assertEquals("0.0", FormatUtils.formatTrafficGb(0f))
    }

    @Test
    fun `formatTrafficGb uses fixed Locale US regardless of default locale`() {
        val originalLocale = Locale.getDefault()
        try {
            // Arabic locale often uses different decimal separators (e.g. ٫)
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("1.5", FormatUtils.formatTrafficGb(1.5f))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun testFormatMinutesDisplayLogic() {
        assertEquals("100 min.", FormatUtils.formatMinutesDisplayLogic(100, 500, false, "min."))
        assertEquals("100 / 500 min.", FormatUtils.formatMinutesDisplayLogic(100, 500, true, "min."))
    }

    @Test
    fun testFormatTrafficDisplayLogic() {
        assertEquals("1.5 GB", FormatUtils.formatTrafficDisplayLogic(1.5f, 5.0f, false, "GB"))
        assertEquals("1.5 / 5.0 GB", FormatUtils.formatTrafficDisplayLogic(1.5f, 5.0f, true, "GB"))
    }

    @Test
    fun `formatTrafficDisplay adds space after remaining_text`() {
        val context = mock<Context>()
        whenever(context.getString(R.string.remaining_text)).thenReturn("Remaining:")
        whenever(context.getString(R.string.gb_abbr)).thenReturn("GB")

        val result = FormatUtils.formatTrafficDisplay(context, 1.5f, 5.0f, true)
        assertEquals("Remaining: 1.5 / 5.0 GB", result)
    }

    @Test
    fun `formatMinutesDisplay adds space after remaining_text`() {
        val context = mock<Context>()
        whenever(context.getString(R.string.remaining_text)).thenReturn("Remaining:")
        whenever(context.getString(R.string.minutes_abbr)).thenReturn("min.")

        val result = FormatUtils.formatMinutesDisplay(context, 100, 500, 0, true)
        assertEquals("Remaining: 100 / 500 min.", result)
    }
}
