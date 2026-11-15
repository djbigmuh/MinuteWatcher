package host.serv.djbigmuh.minutewatcher.core.common.testing

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * A JUnit [TestWatcher] that initializes WorkManager test instance.
 */
class WorkManagerTestRule(
    private val contextProvider: () -> Context,
) : TestWatcher() {
    override fun starting(description: Description) {
        val context = contextProvider()
        val config =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker? = null
                    },
                ).build()
        runCatching<Unit> {
            WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        }
    }
}
