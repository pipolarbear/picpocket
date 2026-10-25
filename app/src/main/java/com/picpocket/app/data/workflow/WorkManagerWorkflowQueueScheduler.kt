package com.picpocket.app.data.workflow

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.picpocket.app.domain.workflow.queue.WorkflowQueueScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps one queue worker alive. WorkManager holds the job until a network
 * connection exists, so a deferred run survives process death and reboot.
 */
@Singleton
class WorkManagerWorkflowQueueScheduler @Inject constructor(
    @ApplicationContext context: Context,
) : WorkflowQueueScheduler {

    // Lazy so WorkManager is first touched after Hilt has injected the worker
    // factory, not while the application graph is still being constructed.
    private val workManager by lazy { WorkManager.getInstance(context) }

    override fun schedule() {
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
    }

    override fun requestDrainNow() = schedule()

    private fun request() = OneTimeWorkRequestBuilder<WorkflowQueueWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    companion object {
        // v2: the v1 work was left FAILED by the default WorkerFactory, which
        // could not build the Hilt worker. A fresh name guarantees a new run.
        const val UNIQUE_NAME = "workflow_deferred_runs_v2"
    }
}
