package com.example.minutewatcher.core.android.util

import com.example.minutewatcher.core.common.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatUtilsTest {
    @Test
    fun testFormatTrafficGb() {
        Locale.setDefault(Locale.US)
        assertEquals("1.5", FormatUtils.formatTrafficGb(1.5f))
        assertEquals("0.0", FormatUtils.formatTrafficGb(0f))
    }

    @Test
    fun testFormatMinutesDisplayLogic() {
        assertEquals("100 min.", FormatUtils.formatMinutesDisplayLogic(100, 500, false, " min."))
        assertEquals("100 min. / 500 min.", FormatUtils.formatMinutesDisplayLogic(100, 500, true, " min."))
    }

    @Test
    fun testFormatTrafficDisplayLogic() {
        Locale.setDefault(Locale.US)
        assertEquals("1.5 GB", FormatUtils.formatTrafficDisplayLogic(1.5f, 5.0f, false, " GB"))
        assertEquals("1.5 GB / 5.0 GB", FormatUtils.formatTrafficDisplayLogic(1.5f, 5.0f, true, " GB"))
    }
}
