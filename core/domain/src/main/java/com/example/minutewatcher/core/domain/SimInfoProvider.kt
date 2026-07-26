package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.ActiveSimInfo
import kotlinx.coroutines.flow.Flow

interface SimInfoProvider {
    fun observeActiveSims(): Flow<List<ActiveSimInfo>>
}
