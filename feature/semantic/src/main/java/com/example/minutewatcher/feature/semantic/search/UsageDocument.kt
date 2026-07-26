package com.example.minutewatcher.feature.semantic.search

import androidx.appsearch.annotation.Document
import androidx.appsearch.app.AppSearchSchema

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
)
