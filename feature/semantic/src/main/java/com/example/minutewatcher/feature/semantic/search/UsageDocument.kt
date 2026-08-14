package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.annotation.Document
import androidx.appsearch.app.AppSearchSchema
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.util.ColorProvider

@Document
data class UsageDocument(
    @get:Document.Namespace
    val namespace: String,
    @get:Document.Id
    val id: String,
    @get:Document.Score
    val score: Int = 0,
    @get:Document.StringProperty(indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
    val operatorName: String,
    @get:Document.StringProperty
    val packageType: String,
    @get:Document.LongProperty
    val remainingValue: Long,
    @get:Document.StringProperty
    val unit: String,
    @get:Document.LongProperty
    val usedValue: Long,
    @get:Document.LongProperty
    val limitValue: Long,
    @get:Document.LongProperty
    val remainingPercent: Long,
    @get:Document.LongProperty
    val periodEnd: Long,
    @get:Document.LongProperty
    val slot: Long,
    @get:Document.StringProperty
    val status: String,
) {
    companion object {
        private const val PERCENT_MULTIPLIER = 100L

        fun fromSemanticEntity(entity: SemanticEntity): UsageDocument {
            val percent = if (entity.limit > 0) (entity.remaining * PERCENT_MULTIPLIER / entity.limit) else 0L
            val usageStatus = ColorProvider.getUsageStatus(entity.remaining.toFloat(), entity.limit.toFloat())

            return UsageDocument(
                namespace = SemanticSearchManager.NAMESPACE,
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
                status = usageStatus.name,
            )
        }

        fun fromGlobalSnapshot(snapshot: GlobalSnapshot): List<UsageDocument> {
            val documents = mutableListOf<UsageDocument>()
            for (sim in snapshot.simSnapshots) {
                if (!sim.isActive) continue
                for (entity in sim.entities.values) {
                    documents.add(fromSemanticEntity(entity))
                }
            }
            return documents
        }
    }
}
