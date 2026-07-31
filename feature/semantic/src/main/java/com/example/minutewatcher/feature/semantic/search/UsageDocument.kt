package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.annotation.Document
import androidx.appsearch.app.AppSearchSchema
import com.example.minutewatcher.core.model.SemanticEntity

@Document
data class UsageDocument(
    @Document.Namespace
    val namespace: String,
    @Document.Id
    val id: String,
    @Document.Score
    val score: Int = 0,
    @Document.StringProperty(indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
    val operatorName: String,
    @Document.StringProperty
    val packageType: String,
    @Document.LongProperty
    val remainingValue: Long,
    @Document.StringProperty
    val unit: String,
    @Document.LongProperty
    val usedValue: Long,
    @Document.LongProperty
    val limitValue: Long,
    @Document.LongProperty
    val remainingPercent: Long,
    @Document.LongProperty
    val periodEnd: Long,
    @Document.LongProperty
    val slot: Long,
    @Document.StringProperty
    val status: String,
) {
    companion object {
        fun fromSemanticEntity(entity: SemanticEntity): UsageDocument {
            val percent = if (entity.limit > 0) (entity.remaining * 100 / entity.limit) else 0L
            return UsageDocument(
                namespace = "usage",
                id = entity.id,
                operatorName = entity.operatorName,
                packageType = entity.packageType.name,
                remainingValue = entity.remaining,
                unit = entity.unit.name,
                usedValue = entity.used,
                limitValue = entity.limit,
                remainingPercent = percent,
                periodEnd = entity.periodEnd,
                slot = entity.simSlot.toLong(),
                status = if (percent < 10) "LOW" else "OK",
            )
        }
    }
}
