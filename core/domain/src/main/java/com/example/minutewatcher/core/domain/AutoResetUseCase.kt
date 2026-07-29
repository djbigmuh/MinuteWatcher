package com.example.minutewatcher.core.domain

import com.example.minutewatcher.core.model.GlobalSnapshot

interface AutoResetUseCase {
    suspend fun performAutoReset(snapshot: GlobalSnapshot)
}
