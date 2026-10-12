package com.example.minutewatcher.service.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

internal object SemanticInitScheduler {
    /**
     * Name of the unique work chain for semantic services initialization.
     */
    const val SEMANTIC_INIT_WORK_NAME = "semantic_init"

    /**
     * Schedules semantic initialization work idempotently using [ExistingWorkPolicy.KEEP].
     *
     * - The work is idempotent and can be run repeatedly without harming data.
     * - Already enqueued or running work is not cancelled or restarted.
     * - A repeated call after successful completion enqueues new work.
     * - Cancelling running initialization would interrupt indexing and shortcut updates without completion guarantee.
     *
     * @param workManager WorkManager instance.
     */
    fun schedule(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<SemanticInitializerWorker>().build()
        workManager.enqueueUniqueWork(
            SEMANTIC_INIT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
