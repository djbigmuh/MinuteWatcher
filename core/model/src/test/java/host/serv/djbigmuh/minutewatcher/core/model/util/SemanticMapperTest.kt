package host.serv.djbigmuh.minutewatcher.core.model.util

import host.serv.djbigmuh.minutewatcher.core.model.Constants
import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.MinutesModel
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.TrafficModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class SemanticMapperTest {
    private val testTimestamp = 1_627_812_000_000L
    private val testZoneId = ZoneId.of("UTC")

    @Test
    fun `getDocumentId returns correct format`() {
        assertEquals("sim_0_minutes", SemanticMapper.getDocumentId(0, PackageType.MINUTES))
        assertEquals("sim_1_data", SemanticMapper.getDocumentId(1, PackageType.DATA))
    }

    @Test
    fun `mapMinutes converts MinutesModel to SemanticEntity correctly`() {
        val model = MinutesModel.calculate(used = 50, limit = 100, manualAdjustment = 0)

        val entity = SemanticMapper.mapMinutes(0, "Test Op", model, 1, testTimestamp, testZoneId)

        assertEquals("sim_0_minutes", entity.id)
        assertEquals(0, entity.simSlot)
        assertEquals("Test Op", entity.operatorName)
        assertEquals(PackageType.MINUTES, entity.packageType)
        assertEquals(100L, entity.limit)
        assertEquals(50L, entity.used)
        assertEquals(50L, entity.remaining)
        assertEquals(MeasurementUnit.MINUTES, entity.unit)
        assertEquals(testTimestamp, entity.lastUpdateTime)
        assertEquals(DataSourceType.SYSTEM, entity.sourceType)
    }

    @Test
    fun `mapMinutes marks as MANUAL when adjustment is present`() {
        val model = MinutesModel.calculate(used = 50, limit = 100, manualAdjustment = 10)
        val entity = SemanticMapper.mapMinutes(0, "Op", model, 1, testTimestamp, testZoneId)
        assertEquals(DataSourceType.MANUAL, entity.sourceType)
    }

    @Test
    fun `mapTraffic converts TrafficModel to SemanticEntity correctly`() {
        val model = TrafficModel.calculate(usedMb = 1024f, limitMb = 2048, manualAdjustmentGb = 0f)

        val entity = SemanticMapper.mapTraffic(1, "Test Op", model, 1, testTimestamp, testZoneId)

        assertEquals("sim_1_data", entity.id)
        assertEquals(1, entity.simSlot)
        assertEquals(PackageType.DATA, entity.packageType)
        assertEquals(2L * Constants.BYTES_PER_GB, entity.limit)
        assertEquals(1L * Constants.BYTES_PER_GB, entity.used)
        assertEquals(1L * Constants.BYTES_PER_GB, entity.remaining)
        assertEquals(MeasurementUnit.BYTES, entity.unit)
        assertEquals(DataSourceType.SYSTEM, entity.sourceType)
    }

    @Test
    fun `mapTraffic marks as MANUAL when adjustment is present`() {
        val model = TrafficModel.calculate(usedMb = 100f, limitMb = 1000, manualAdjustmentGb = 1.0f)
        val entity = SemanticMapper.mapTraffic(0, "Op", model, 1, testTimestamp, testZoneId)
        assertEquals(DataSourceType.MANUAL, entity.sourceType)
    }
}
