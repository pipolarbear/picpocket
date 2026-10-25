package com.picpocket.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A stored workflow rule. The rule tree (triggers/conditions/roots) is JSON. */
@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val json: String,
)

/** A recorded workflow run (per-node outcomes are JSON). */
@Entity(tableName = "workflow_runs")
data class WorkflowRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workflowId: Long,
    val workflowName: String,
    val trigger: String,
    val startedAt: Long,
    val finishedAt: Long,
    val status: String,
    val nodesJson: String,
    val documentId: String? = null,
    val documentName: String? = null,
)

/**
 * A workflow run deferred because the device was offline. Replayed by the queue
 * worker once a network connection returns.
 */
@Entity(tableName = "workflow_pending_runs")
data class WorkflowPendingRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workflowId: Long,
    val workflowName: String,
    val trigger: String,
    val documentId: String,
    val documentName: String? = null,
    val createdAt: Long,
)
