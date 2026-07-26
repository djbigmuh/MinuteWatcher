package com.example.minutewatcher.core.data.semantic

import com.example.minutewatcher.core.model.*
import com.example.minutewatcher.core.model.MinutesModel
import com.example.minutewatcher.core.model.TrafficModel
import com.example.minutewatcher.core.model.util.BillingPeriodCalculator

object SemanticMapper {

    fun mapMinutes(slot: Int, operator: String, model: MinutesModel, resetDay: Int): SemanticEntity {
        val totalLimit = model.limit.toLong() + model.manualAdjustment
        val (periodStart, periodEnd) = calculatePeriod(resetDay)
        
        return SemanticEntity(
            id = "sim_${slot}_minutes",
            simSlot = slot,
            operatorName = operator,
            packageType = PackageType.MINUTES,
            limit = totalLimit,
            used = model.used.toLong(),
            remaining = maxOf(0L, model.remaining.toLong()),
            unit = MeasurementUnit.MINUTES,
            periodStart = periodStart,
            periodEnd = periodEnd,
            lastUpdateTime = System.currentTimeMillis(),
            sourceType = if (model.manualAdjustment != 0) DataSourceType.MANUAL else DataSourceType.SYSTEM,
            isStale = false,
            metadata = mapOf("reset_day" to resetDay)
        )
    }

    fun mapTraffic(slot: Int, operator: String, model: TrafficModel, resetDay: Int): SemanticEntity {
        val bytesPerMb = 1024L * 1024L
        val limitBytes = (model.limitMb * bytesPerMb) + (model.manualAdjustmentGb * 1024 * bytesPerMb).toLong()
        val usedBytes = (model.usedMb * bytesPerMb).toLong()
        val remainingBytes = if (model.remainingGb >= 0) (model.remainingGb * 1024 * bytesPerMb).toLong() else 0L
        val (periodStart, periodEnd) = calculatePeriod(resetDay)

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
            lastUpdateTime = System.currentTimeMillis(),
            sourceType = if (model.manualAdjustmentGb != 0f) DataSourceType.MANUAL else DataSourceType.SYSTEM,
            isStale = false,
            metadata = mapOf("reset_day" to resetDay)
        )
    }

    private fun calculatePeriod(resetDay: Int): Pair<Long, Long> {
        val info = BillingPeriodCalculator.calculate(resetDay)
        return info.startDate to info.endDate
    }
}
