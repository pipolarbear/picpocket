package com.picpocket.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.picpocket.app.data.local.entity.WorkflowRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowRunDao {

    @Insert
    suspend fun insert(entity: WorkflowRunEntity): Long

    @Query("SELECT * FROM workflow_runs WHERE workflowId = :workflowId ORDER BY startedAt DESC")
    fun observeByWorkflow(workflowId: Long): Flow<List<WorkflowRunEntity>>

    @Query("DELETE FROM workflow_runs WHERE workflowId = :workflowId")
    suspend fun clearForWorkflow(workflowId: Long)

    @Query("DELETE FROM workflow_runs")
    suspend fun clearAll()

    @Query(
        "DELETE FROM workflow_runs WHERE workflowId = :workflowId AND id NOT IN (" +
            "SELECT id FROM workflow_runs WHERE workflowId = :workflowId " +
            "ORDER BY startedAt DESC LIMIT :keep)"
    )
    suspend fun pruneForWorkflow(workflowId: Long, keep: Int)
}
