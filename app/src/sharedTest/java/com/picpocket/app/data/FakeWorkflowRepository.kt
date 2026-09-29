package com.picpocket.app.data

import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.data.repository.WorkflowRun
import com.picpocket.app.domain.workflow.model.RunResult
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory WorkflowRepository for unit tests. */
class FakeWorkflowRepository : WorkflowRepository {

    private val workflows = MutableStateFlow<List<Workflow>>(emptyList())
    private val runs = mutableListOf<WorkflowRun>()
    private var nextId = 1L

    override fun observeWorkflows(): Flow<List<Workflow>> = workflows

    override suspend fun listEnabled(): List<Workflow> = workflows.value.filter { it.enabled }

    override suspend fun get(id: Long): Workflow? = workflows.value.find { it.id == id }

    override suspend fun save(workflow: Workflow): Long {
        val id = if (workflow.id == 0L) nextId++ else workflow.id
        val stored = workflow.copy(id = id)
        workflows.value = workflows.value.filter { it.id != id } + stored
        return id
    }

    override suspend fun delete(id: Long) {
        workflows.value = workflows.value.filter { it.id != id }
    }

    override suspend fun clone(id: Long): Long {
        val source = get(id) ?: return 0
        return save(source.copy(id = 0, name = "${source.name} copy"))
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        workflows.value = workflows.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override suspend fun recordRun(
        workflow: Workflow,
        trigger: TriggerEvent,
        startedAt: Long,
        result: RunResult,
    ) {
        runs.add(
            WorkflowRun(
                id = runs.size + 1L,
                workflowId = workflow.id,
                workflowName = workflow.name,
                trigger = trigger,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                status = result.status,
                nodes = result.nodes,
            ),
        )
    }

    override fun observeRuns(workflowId: Long): Flow<List<WorkflowRun>> =
        MutableStateFlow(runs.filter { it.workflowId == workflowId })

    override suspend fun clearHistory(workflowId: Long?) {
        if (workflowId == null) runs.clear() else runs.removeAll { it.workflowId == workflowId }
    }
}
