package com.example.minutewatcher.core.domain

import kotlinx.coroutines.flow.Flow

interface SimIdentitySeedRepository {
    fun getSeed(): Flow<String>

    suspend fun setSeed(seed: String)
}
