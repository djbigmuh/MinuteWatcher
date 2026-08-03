package com.example.minutewatcher.core.domain

interface AutoResetUseCase {
    suspend fun performAutoReset(shouldNotify: Boolean = true)
}
