package com.example.minutewatcher.core.data.semantic

import com.example.minutewatcher.core.model.SchemaVersion
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SemanticVersion @Inject constructor() {
    fun getVersion(): SchemaVersion {
        return SchemaVersion(1, 0, 0)
    }
}
