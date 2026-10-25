package com.picpocket.app.data.repository

import com.picpocket.app.data.local.dao.WorkflowDao
import com.picpocket.app.data.local.dao.WorkflowPendingRunDao
import com.picpocket.app.data.local.dao.WorkflowRunDao
import com.picpocket.app.data.local.entity.WorkflowEntity
import com.picpocket.app.data.local.entity.WorkflowPendingRunEntity
import com.picpocket.app.data.local.entity.WorkflowRunEntity
import com.picpocket.app.domain.workflow.model.NodeResult
import com.picpocket.app.domain.workflow.model.RunResult
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.domain.workflow.queue.WorkflowQueueScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A recorded workflow run with its per-node outcomes. */
data class WorkflowRun(
    val id: Long,
    val workflowId: Long,
    val workflowName: String,
    val trigger: TriggerEvent,
    val startedAt: Long,
    val finishedAt: Long,
    val status: RunStatus,
    val nodes: List<NodeResult>,
    val documentId: String? = null,
    val documentName: String? = null,
)

/** A workflow run waiting for a network connection. */
data class DeferredRun(
    val id: Long,
    val workflowId: Long,
    val workflowName: String,
    val trigger: TriggerEvent,
    val documentId: String,
    val documentName: String? = null,
    val createdAt: Long,
)

interface WorkflowRepository {
    fun observeWorkflows(): Flow<List<Workflow>>
    suspend fun listEnabled(): List<Workflow>
    suspend fun get(id: Long): Workflow?
    suspend fun save(workflow: Workflow): Long
    suspend fun delete(id: Long)
    suspend fun clone(id: Long): Long
    suspend fun setEnabled(id: Long, enabled: Boolean)
    suspend fun recordRun(
        workflow: Workflow,
        trigger: TriggerEvent,
        documentId: String?,
        documentName: String?,
        startedAt: Long,
        result: RunResult,
    )
    fun observeRuns(workflowId: Long): Flow<List<WorkflowRun>>
    suspend fun clearHistory(workflowId: Long?)

    /** Queues [workflow] to run for [documentId] once a network connection returns. */
    suspend fun enqueueDeferred(
        workflow: Workflow,
        trigger: TriggerEvent,
        documentId: String,
        documentName: String?,
    )

    /** Queued runs, oldest first. */
    suspend fun listDeferred(): List<DeferredRun>

    suspend fun removeDeferred(id: Long)

    /** Drops queued runs for a workflow that was deleted or disabled. */
    suspend fun clearDeferredForWorkflow(workflowId: Long)

    fun observeDeferredCount(): Flow<Int>

    companion object { const val HISTORY_LIMIT = 50 }
}

@Singleton
class WorkflowRepositoryImpl @Inject constructor(
    private val workflowDao: WorkflowDao,
    private val runDao: WorkflowRunDao,
    private val pendingRunDao: WorkflowPendingRunDao,
    private val queueScheduler: WorkflowQueueScheduler,
) : WorkflowRepository {

    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun observeWorkflows(): Flow<List<Workflow>> =
        workflowDao.observeAll().map { list -> list.map { it.toDomain(codec) } }

    override suspend fun listEnabled(): List<Workflow> =
        workflowDao.getEnabled().map { it.toDomain(codec) }

    override suspend fun get(id: Long): Workflow? = workflowDao.getById(id)?.toDomain(codec)

    override suspend fun save(workflow: Workflow): Long =
        workflowDao.upsert(
            WorkflowEntity(
                id = workflow.id,
                name = workflow.name,
                enabled = workflow.enabled,
                json = codec.encodeToString(Workflow.serializer(), workflow.copy(id = 0)),
            ),
        )

    override suspend fun delete(id: Long) {
        workflowDao.deleteById(id)
        runDao.clearForWorkflow(id)
        pendingRunDao.clearForWorkflow(id)
    }

    override suspend fun clone(id: Long): Long {
        val source = workflowDao.getById(id) ?: return 0
        val copy = codec.decodeFromString(Workflow.serializer(), source.json)
        return save(copy.copy(id = 0, name = "${source.name} copy"))
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        workflowDao.setEnabled(id, enabled)
        // A disabled workflow should not fire from a queue it built while enabled.
        if (!enabled) pendingRunDao.clearForWorkflow(id)
    }

    override suspend fun recordRun(
        workflow: Workflow,
        trigger: TriggerEvent,
        documentId: String?,
        documentName: String?,
        startedAt: Long,
        result: RunResult,
    ) {
        runDao.insert(
            WorkflowRunEntity(
                workflowId = workflow.id,
                workflowName = workflow.name,
                trigger = trigger.value,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                status = result.status.name,
                nodesJson = codec.encodeToString(ListSerializer(NodeResult.serializer()), result.nodes),
                documentId = documentId,
                documentName = documentName,
            ),
        )
        runDao.pruneForWorkflow(workflow.id, WorkflowRepository.HISTORY_LIMIT)
    }

    override fun observeRuns(workflowId: Long): Flow<List<WorkflowRun>> =
        runDao.observeByWorkflow(workflowId).map { list -> list.map { it.toDomain(codec) } }

    override suspend fun clearHistory(workflowId: Long?) {
        if (workflowId == null) runDao.clearAll() else runDao.clearForWorkflow(workflowId)
    }

    override suspend fun enqueueDeferred(
        workflow: Workflow,
        trigger: TriggerEvent,
        documentId: String,
        documentName: String?,
    ) {
        pendingRunDao.insert(
            WorkflowPendingRunEntity(
                workflowId = workflow.id,
                workflowName = workflow.name,
                trigger = trigger.value,
                documentId = documentId,
                documentName = documentName,
                createdAt = System.currentTimeMillis(),
            ),
        )
        queueScheduler.schedule()
    }

    override suspend fun listDeferred(): List<DeferredRun> =
        pendingRunDao.getAll().map { it.toDeferred() }

    override suspend fun removeDeferred(id: Long) = pendingRunDao.delete(id)

    override suspend fun clearDeferredForWorkflow(workflowId: Long) =
        pendingRunDao.clearForWorkflow(workflowId)

    override fun observeDeferredCount(): Flow<Int> = pendingRunDao.observeCount()
}

private fun WorkflowEntity.toDomain(codec: Json): Workflow =
    codec.decodeFromString(Workflow.serializer(), json).copy(id = id, name = name, enabled = enabled)

private fun WorkflowPendingRunEntity.toDeferred(): DeferredRun =
    DeferredRun(
        id = id,
        workflowId = workflowId,
        workflowName = workflowName,
        trigger = TriggerEvent.from(trigger) ?: TriggerEvent.MANUAL,
        documentId = documentId,
        documentName = documentName,
        createdAt = createdAt,
    )

private fun WorkflowRunEntity.toDomain(codec: Json): WorkflowRun =
    WorkflowRun(
        id = id,
        workflowId = workflowId,
        workflowName = workflowName,
        trigger = TriggerEvent.from(trigger) ?: TriggerEvent.MANUAL,
        startedAt = startedAt,
        finishedAt = finishedAt,
        status = runCatching { RunStatus.valueOf(status) }.getOrDefault(RunStatus.SUCCESS),
        nodes = codec.decodeFromString(ListSerializer(NodeResult.serializer()), nodesJson),
        documentId = documentId,
        documentName = documentName,
    )
