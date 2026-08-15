package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.annotation.Document
import androidx.appsearch.annotation.Document.Id
import androidx.appsearch.annotation.Document.LongProperty
import androidx.appsearch.annotation.Document.Namespace
import androidx.appsearch.annotation.Document.Score
import androidx.appsearch.annotation.Document.StringProperty
import androidx.appsearch.app.AppSearchSchema
import com.example.minutewatcher.core.model.GlobalSnapshot
import com.example.minutewatcher.core.model.SemanticEntity
import com.example.minutewatcher.core.model.util.ColorProvider

/**
 * DTO-документ для AppSearch-индексации.
 *
 * Плоская схема обусловлена требованием AppSearch: все поля одного документа
 * должны находиться в одном классе.
 *
 * Строгое соблюдение API: структура полей и аннотации соответствуют
 * контракту [androidx.appsearch.annotation.Document].
 * Изменение набора полей требует инкремента версии схемы
 * и обработки миграции в [SemanticSearchManager].
 */
@Document
// UsageDocument is a DTO for AppSearch, flat schema requires all fields in one class.
@Suppress("LongParameterList")
class UsageDocument {
    @Namespace
    var namespace: String = ""

    @Id
    var id: String = ""

    @Score
    var score: Int = 0

    @StringProperty(indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
    var operatorName: String = ""

    @StringProperty
    var packageType: String = ""

    @LongProperty
    var remainingValue: Long = 0

    @StringProperty
    var unit: String = ""

    @LongProperty
    var usedValue: Long = 0

    @LongProperty
    var limitValue: Long = 0

    @LongProperty
    var remainingPercent: Long = 0

    @LongProperty
    var periodEnd: Long = 0

    @LongProperty
    var slot: Long = 0

    @StringProperty
    var status: String = ""

    constructor()

    constructor(
        namespace: String,
        id: String,
        score: Int,
        operatorName: String,
        packageType: String,
        remainingValue: Long,
        unit: String,
        usedValue: Long,
        limitValue: Long,
        remainingPercent: Long,
        periodEnd: Long,
        slot: Long,
        status: String,
    ) {
        this.namespace = namespace
        this.id = id
        this.score = score
        this.operatorName = operatorName
        this.packageType = packageType
        this.remainingValue = remainingValue
        this.unit = unit
        this.usedValue = usedValue
        this.limitValue = limitValue
        this.remainingPercent = remainingPercent
        this.periodEnd = periodEnd
        this.slot = slot
        this.status = status
    }

    companion object {
        private const val PERCENT_MAX = 100

        fun fromSemanticEntity(entity: SemanticEntity): UsageDocument {
            val percent = if (entity.limit > 0) (entity.remaining * PERCENT_MAX / entity.limit) else 0L
            val usageStatus = ColorProvider.getUsageStatus(entity.remaining.toFloat(), entity.limit.toFloat()).name

            return UsageDocument(
                namespace = SemanticSearchManager.NAMESPACE,
                id = entity.id,
                score = 0,
                operatorName = entity.operatorName,
                packageType = entity.packageType.name,
                remainingValue = entity.remaining,
                unit = entity.unit.name,
                usedValue = entity.used,
                limitValue = entity.limit,
                remainingPercent = percent,
                periodEnd = entity.periodEnd,
                slot = entity.simSlot.toLong(),
                status = usageStatus,
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
