package com.example.minutewatcher.core.domain

interface BenchmarkUseCase {
    /**
     * Fills the local storage with mock data for benchmarking purposes.
     * @param simCount Number of mock SIM cards to create (1 or 2).
     */
    suspend fun populateMockData(simCount: Int)
}
