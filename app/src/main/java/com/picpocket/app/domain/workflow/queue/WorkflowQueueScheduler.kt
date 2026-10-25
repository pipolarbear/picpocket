package com.picpocket.app.domain.workflow.queue

/** Schedules replays of queued workflow runs (see `WorkflowRepository.enqueueDeferred`). */
interface WorkflowQueueScheduler {
    /** Ensures a queue worker is scheduled; safe to call repeatedly. */
    fun schedule()

    /** Requests an immediate attempt, e.g. when the app comes to the foreground. */
    fun requestDrainNow()
}
