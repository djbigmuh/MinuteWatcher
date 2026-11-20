package host.serv.djbigmuh.minutewatcher.core.model.util

import host.serv.djbigmuh.minutewatcher.core.model.DataSourceType
import host.serv.djbigmuh.minutewatcher.core.model.MeasurementUnit
import host.serv.djbigmuh.minutewatcher.core.model.MinutesModel
import host.serv.djbigmuh.minutewatcher.core.model.PackageType
import host.serv.djbigmuh.minutewatcher.core.model.SemanticEntity
import host.serv.djbigmuh.minutewatcher.core.model.TrafficModel
import java.time.ZoneId
import java.util.Locale

object SemanticMapper {
    /**
     * Returns a stable document identifier for AppSearch.
     *
     * Contract: "sim_${slot}_${typeName.lowercase(Locale.ROOT)}"
     */
    fun getDocumentId(
        slot: Int,
        typeName: String,
    ): String = "sim_${slot}_${typeName.lowercase(Locale.ROOT)}"

    fun getDocumentId(
        slot: Int,
        type: PackageType,
    ): String = getDocumentId(slot, type.name)

    fun mapMinutes(
        slot: Int,
        operatorName: String,
        model: MinutesModel,
        resetDay: Int,
        nowMillis: Long,
        zoneId: ZoneId,
    ): SemanticEntity {
        val (periodStart, periodEnd) = calculatePeriod(resetDay, nowMillis, zoneId)

        return SemanticEntity(
            id = getDocumentId(slot, PackageType.MINUTES),
            simSlot = slot,
            operatorName = operatorName,
            packageType = PackageType.MINUTES,
            limit = model.effectiveLimit.toLong(),
            used = model.used.toLong(),
            remaining = maxOf(0L, model.remaining.toLong()),
            unit = MeasurementUnit.MINUTES,
            periodStart = periodStart,
            periodEnd = periodEnd,
            lastUpdateTime = nowMillis,
            sourceType = if (model.manualAdjustment != 0) DataSourceType.MANUAL else DataSourceType.SYSTEM,
        )
    }

    fun mapTraffic(
        slot: Int,
        operatorName: String,
        model: TrafficModel,
        resetDay: Int,
        nowMillis: Long,
        zoneId: ZoneId,
    ): SemanticEntity {
        val limitBytes = UnitConversionUtils.gbToBytes(model.effectiveLimitGb)
        val usedBytes = UnitConversionUtils.mbToBytes(model.usedMb)
        val remainingBytes =
            if (model.remainingGb != null) {
                UnitConversionUtils.gbToBytes(model.remainingGb)
            } else {
                0L
            }
        val (periodStart, periodEnd) = calculatePeriod(resetDay, nowMillis, zoneId)

        return SemanticEntity(
            id = getDocumentId(slot, PackageType.DATA),
            simSlot = slot,
            operatorName = operatorName,
            packageType = PackageType.DATA,
            limit = limitBytes,
            used = usedBytes,
            remaining = remainingBytes,
            unit = MeasurementUnit.BYTES,
            periodStart = periodStart,
            periodEnd = periodEnd,
            lastUpdateTime = nowMillis,
            sourceType = if (model.manualAdjustmentGb != 0f) DataSourceType.MANUAL else DataSourceType.SYSTEM,
        )
    }

    private fun calculatePeriod(
        resetDay: Int,
        nowMillis: Long,
        zoneId: ZoneId,
    ): Pair<Long, Long> {
        val info = BillingPeriodCalculator.calculate(resetDay, nowMillis, zoneId)
        return info.startDate to info.endDate
    }
}
