package com.picpocket.app.data.workflow

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.picpocket.app.domain.workflow.queue.DeferredRunProcessor
import com.picpocket.app.domain.workflow.queue.DrainOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Replays queued workflow runs; constrained to a network connection. */
@HiltWorker
class WorkflowQueueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val processor: DeferredRunProcessor,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            when (processor.process()) {
                DrainOutcome.RETRY -> Result.retry()
                DrainOutcome.DONE,
                DrainOutcome.WAITING_FOR_FOREGROUND,
                -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
