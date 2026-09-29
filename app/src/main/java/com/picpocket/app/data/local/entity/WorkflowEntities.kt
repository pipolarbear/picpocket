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
)
