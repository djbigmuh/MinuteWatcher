package host.serv.djbigmuh.minutewatcher.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ClampingUtilsTest {
    private val hardLimitInt = 44640
    private val hardLimitFloat = 10240f

    @Test
    fun `clamp boundary cases for Int`() {
        // 0 case
        assertEquals(0, ClampingUtils.clamp(0, 0, hardLimitInt))

        // negative case
        assertEquals(0, ClampingUtils.clamp(-100, 0, hardLimitInt))

        // > hardLimit case
        assertEquals(hardLimitInt, ClampingUtils.clamp(50000, 0, hardLimitInt))

        // Long MAX_VALUE case (simulated with large Int if applicable, or just very large)
        assertEquals(hardLimitInt, ClampingUtils.clamp(Int.MAX_VALUE, 0, hardLimitInt))
    }

    @Test
    fun `clamp boundary cases for Float`() {
        // 0 case
        assertEquals(0f, ClampingUtils.clamp(0f, 0f, hardLimitFloat))

        // negative case
        assertEquals(0f, ClampingUtils.clamp(-1f, 0f, hardLimitFloat))

        // > hardLimit case
        assertEquals(hardLimitFloat, ClampingUtils.clamp(11000f, 0f, hardLimitFloat))

        // Very large case
        assertEquals(hardLimitFloat, ClampingUtils.clamp(Float.MAX_VALUE, 0f, hardLimitFloat))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `clamp throws if min gt max`() {
        ClampingUtils.clamp(10, 100, 0)
    }

    @Test
    fun `clamp returns value if within range`() {
        assertEquals(50, ClampingUtils.clamp(50, 0, 100))
        assertEquals(50.5f, ClampingUtils.clamp(50.5f, 0f, 100f))
    }
}
