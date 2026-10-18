package host.serv.djbigmuh.minutewatcher.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageModelsTest {
    @Test
    fun `MinutesModel handles negative adjustment by clamping effectiveLimit to zero`() {
        val model =
            MinutesModel.calculate(
                used = 10,
                limit = 100,
                manualAdjustment = -200,
            )

        assertEquals(0, model.effectiveLimit)
        assertEquals(0, model.remaining)
    }

    @Test
    fun `MinutesModel handles positive adjustment correctly`() {
        val model =
            MinutesModel.calculate(
                used = 10,
                limit = 0,
                manualAdjustment = 50,
            )

        assertEquals(50, model.effectiveLimit)
        assertEquals(40, model.remaining)
    }

    @Test
    fun `MinutesModel caps effectiveLimit at maximum limit when limit plus adjustment exceeds max Int`() {
        val model =
            MinutesModel.calculate(
                used = 0,
                limit = Int.MAX_VALUE,
                manualAdjustment = 10,
            )

        assertEquals(Constants.MAX_MINUTES_LIMIT, model.effectiveLimit)
        assertEquals(Constants.MAX_MINUTES_LIMIT, model.remaining)
    }

    @Test
    fun `MinutesModel clamps effectiveLimit to zero when adjustment is minimum Int`() {
        val model =
            MinutesModel.calculate(
                used = 0,
                limit = 0,
                manualAdjustment = Int.MIN_VALUE,
            )

        assertEquals(0, model.effectiveLimit)
        assertEquals(0, model.remaining)
    }

    @Test
    fun `MinutesModel caps remaining usage at maximum limit when used minutes is minimum Int`() {
        val model =
            MinutesModel.calculate(
                used = Int.MIN_VALUE,
                limit = 100,
                manualAdjustment = 0,
            )

        assertTrue(model.remaining >= 0)
        assertEquals(Constants.MAX_MINUTES_LIMIT, model.remaining)
    }

    @Test
    fun `MinutesModel produces expected values for standard usage inputs`() {
        val model =
            MinutesModel.calculate(
                used = 30,
                limit = 100,
                manualAdjustment = -10,
            )

        assertEquals(90, model.effectiveLimit)
        assertEquals(60, model.remaining)
        assertTrue(model.isLimitSet)
    }

    @Test
    fun `TrafficModel handles negative adjustment by clamping effectiveLimitGb to zero`() {
        val model =
            TrafficModel.calculate(
                usedMb = 512f,
                limitMb = 0,
                manualAdjustmentGb = -5f,
            )

        assertEquals(0f, model.effectiveLimitGb)
        assertEquals(0f, model.remainingGb)
    }

    @Test
    fun `TrafficModel calculates remainingGb correctly with adjustment`() {
        val model =
            TrafficModel.calculate(
                usedMb = 1024f, // 1 GB
                limitMb = 2048, // 2 GB
                manualAdjustmentGb = 1f, // +1 GB
            )

        assertEquals(3f, model.effectiveLimitGb)
        assertEquals(2f, model.remainingGb)
    }

    @Test
    fun `TrafficModel returns null remainingGb when limit is not set`() {
        val model =
            TrafficModel.calculate(
                usedMb = 512f,
                limitMb = 0,
                manualAdjustmentGb = 0f,
            )

        assertFalse(model.isLimitSet)
        assertNull(model.remainingGb)
    }
}
