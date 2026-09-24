package com.example.minutewatcher.service.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

internal object SemanticInitScheduler {
    const val SEMANTIC_INIT_WORK_NAME = "semantic_init"

    fun schedule(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<SemanticInitializerWorker>().build()
        workManager.enqueueUniqueWork(
            SEMANTIC_INIT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
