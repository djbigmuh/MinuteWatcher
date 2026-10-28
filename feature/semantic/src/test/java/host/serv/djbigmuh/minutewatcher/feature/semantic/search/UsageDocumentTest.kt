package host.serv.djbigmuh.minutewatcher.feature.semantic.search

import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.util.UsageStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageDocumentTest {
    @Test
    fun `fromSemanticEntity sets status normal when remaining is exactly twenty percent`() {
        val limit = 83_886_085L
        val remaining = 16_777_217L
        val entity =
            SemanticEntity(
                id = "0_m",
                simSlot = 0,
                operatorName = "MTS",
                packageType = PackageType.MINUTES,
                used = limit - remaining,
                limit = limit,
                remaining = remaining,
                unit = MeasurementUnit.MINUTES,
                periodStart = 0L,
                periodEnd = 5000L,
                lastUpdateTime = 0L,
                sourceType = DataSourceType.SYSTEM,
            )

        val document = UsageDocument.fromSemanticEntity(entity)
        assertEquals(UsageStatus.NORMAL.name, document.status)
    }

    @Test
    fun `fromSemanticEntity sets status low and percent when remaining is nineteen point five percent`() {
        val limit = 22_011_707_392L
        val remaining = 4_294_967_296L
        val entity =
            SemanticEntity(
                id = "0_t",
                simSlot = 0,
                operatorName = "MTS",
                packageType = PackageType.DATA,
                used = limit - remaining,
                limit = limit,
                remaining = remaining,
                unit = MeasurementUnit.BYTES,
                periodStart = 0L,
                periodEnd = 5000L,
                lastUpdateTime = 0L,
                sourceType = DataSourceType.SYSTEM,
            )

        val document = UsageDocument.fromSemanticEntity(entity)
        assertEquals(UsageStatus.LOW.name, document.status)
        assertEquals(19L, document.remainingPercent)
    }
}
