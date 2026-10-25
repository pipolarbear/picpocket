package com.picpocket.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.picpocket.app.data.local.entity.WorkflowPendingRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowPendingRunDao {

    @Insert
    suspend fun insert(entity: WorkflowPendingRunEntity): Long

    @Query("SELECT * FROM workflow_pending_runs ORDER BY createdAt ASC")
    suspend fun getAll(): List<WorkflowPendingRunEntity>

    @Query("SELECT COUNT(*) FROM workflow_pending_runs")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM workflow_pending_runs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM workflow_pending_runs WHERE workflowId = :workflowId")
    suspend fun clearForWorkflow(workflowId: Long)
}
