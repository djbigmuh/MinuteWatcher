package com.example.minutewatcher.core.model.util

import com.example.minutewatcher.core.model.Constants
import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.TrafficModel
import java.util.Locale

object SemanticMapper {
    @JvmStatic
    fun getDocumentId(
        slot: Int,
        type: PackageType,
    ): String = "sim_${slot}_${type.name.lowercase(Locale.ROOT)}"

    @JvmStatic
    fun mapMinutes(
        slot: Int,
        operatorName: String,
        model: MinutesModel,
        resetDay: Int,
        nowMillis: Long,
    ): SemanticEntity {
        val (periodStart, periodEnd) = calculatePeriod(resetDay, nowMillis)

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

    @JvmStatic
    fun mapTraffic(
        slot: Int,
        operatorName: String,
        model: TrafficModel,
        resetDay: Int,
        nowMillis: Long,
    ): SemanticEntity {
        val limitBytes = (model.effectiveLimitGb.toDouble() * Constants.BYTES_PER_GB).toLong()
        val usedBytes = (model.usedMb.toDouble() * Constants.BYTES_PER_MB).toLong()
        val remainingBytes =
            if (model.remainingGb >= 0) {
                (model.remainingGb.toDouble() * Constants.BYTES_PER_GB).toLong()
            } else {
                0L
            }
        val (periodStart, periodEnd) = calculatePeriod(resetDay, nowMillis)

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
    ): Pair<Long, Long> {
        val info = BillingPeriodCalculator.calculate(resetDay, nowMillis)
        return info.startDate to info.endDate
    }
}
