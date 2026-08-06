package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow

interface SimIdentitySeedRepository {
    val seedFlow: Flow<String>

    suspend fun setSeed(seed: String)

    suspend fun getOrCreateSeed(generator: () -> String): String
}
