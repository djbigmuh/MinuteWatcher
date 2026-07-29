package com.example.minutewatcher.core.data.semantic

import com.example.minutewatcher.core.model.DataSourceType
import com.example.minutewatcher.core.model.MeasurementUnit
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.PackageType
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.TrafficModel
import com.example.minutewatcher.core.model.util.BillingPeriodCalculator

object SemanticMapper {
    fun mapMinutes(
        slot: Int,
        operator: String,
        model: MinutesModel,
        resetDay: Int,
        nowMillis: Long,
    ): SemanticEntity {
        val (periodStart, periodEnd) = calculatePeriod(resetDay, nowMillis)

        return SemanticEntity(
            id = "sim_${slot}_minutes",
            simSlot = slot,
            operatorName = operator,
            packageType = PackageType.MINUTES,
            limit = model.effectiveLimit.toLong(),
            used = model.used.toLong(),
            remaining = maxOf(0L, model.remaining.toLong()),
            unit = MeasurementUnit.MINUTES,
            periodStart = periodStart,
            periodEnd = periodEnd,
            lastUpdateTime = nowMillis,
            sourceType = if (model.manualAdjustment != 0) DataSourceType.MANUAL else DataSourceType.SYSTEM,
            metadata = mapOf("reset_day" to resetDay),
        )
    }

    fun mapTraffic(
        slot: Int,
        operator: String,
        model: TrafficModel,
        resetDay: Int,
        nowMillis: Long,
    ): SemanticEntity {
        val bytesPerMb = 1024L * 1024L
        val limitBytes = (model.effectiveLimitGb * 1024 * bytesPerMb).toLong()
        val usedBytes = (model.usedMb * bytesPerMb).toLong()
        val remainingBytes = if (model.remainingGb >= 0) (model.remainingGb * 1024 * bytesPerMb).toLong() else 0L
        val (periodStart, periodEnd) = calculatePeriod(resetDay, nowMillis)

        return SemanticEntity(
            id = "sim_${slot}_data",
            simSlot = slot,
            operatorName = operator,
            packageType = PackageType.DATA,
            limit = limitBytes,
            used = usedBytes,
            remaining = remainingBytes,
            unit = MeasurementUnit.BYTES,
            periodStart = periodStart,
            periodEnd = periodEnd,
            lastUpdateTime = nowMillis,
            sourceType = if (model.manualAdjustmentGb != 0f) DataSourceType.MANUAL else DataSourceType.SYSTEM,
            metadata = mapOf("reset_day" to resetDay),
        )
    }

    private fun calculatePeriod(resetDay: Int, nowMillis: Long): Pair<Long, Long> {
        val info = BillingPeriodCalculator.calculate(resetDay, nowMillis)
        return info.startDate to info.endDate
    }
}
