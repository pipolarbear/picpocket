package com.picpocket.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.local.PicPocketDatabase
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.data.repository.WorkflowRepositoryImpl
import com.picpocket.app.domain.workflow.FakeWorkflowQueueScheduler
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.RunResult
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkflowRepositoryTest {

    private lateinit var db: PicPocketDatabase
    private lateinit var repo: WorkflowRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PicPocketDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = WorkflowRepositoryImpl(
            db.workflowDao(),
            db.workflowRunDao(),
            db.workflowPendingRunDao(),
            FakeWorkflowQueueScheduler(),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun workflow(name: String = "w") = Workflow(
        name = name,
        triggers = listOf(TriggerEvent.DOC_CREATED),
        roots = listOf(ActionNode("a", ActionType.ZIP)),
    )

    @Test
    fun `save then read round-trips the rule`() = runTest {
        val id = repo.save(workflow("Receipts"))
        val loaded = repo.get(id)
        assertNotNull(loaded)
        assertEquals("Receipts", loaded!!.name)
        assertEquals(listOf(TriggerEvent.DOC_CREATED), loaded.triggers)
        assertEquals(ActionType.ZIP, loaded.roots.first().type)
        assertEquals(1, repo.observeWorkflows().first().size)
    }

    @Test
    fun `clone copies with a new id and name`() = runTest {
        val id = repo.save(workflow("Original"))
        val copyId = repo.clone(id)
        val copy = repo.get(copyId)
        assertEquals("Original copy", copy!!.name)
        assertEquals(2, repo.observeWorkflows().first().size)
    }

    @Test
    fun `delete removes the workflow and its runs`() = runTest {
        val id = repo.save(workflow())
        repo.recordRun(workflow(), TriggerEvent.DOC_CREATED, null, null, 0L, RunResult(RunStatus.SUCCESS, emptyList()))
        repo.delete(id)
        assertNull(repo.get(id))
        assertEquals(0, repo.observeRuns(id).first().size)
    }

    @Test
    fun `history is pruned to the limit`() = runTest {
        val id = repo.save(workflow())
        val saved = workflow().copy(id = id)
        repeat(WorkflowRepository.HISTORY_LIMIT + 5) { i ->
            repo.recordRun(saved, TriggerEvent.DOC_CREATED, null, null, i.toLong(), RunResult(RunStatus.SUCCESS, emptyList()))
        }
        assertEquals(WorkflowRepository.HISTORY_LIMIT, repo.observeRuns(id).first().size)
    }

    @Test
    fun `clearHistory empties the runs`() = runTest {
        val id = repo.save(workflow())
        repo.recordRun(workflow().copy(id = id), TriggerEvent.DOC_CREATED, null, null, 0L, RunResult(RunStatus.FAILURE, emptyList()))
        repo.clearHistory(id)
        assertEquals(0, repo.observeRuns(id).first().size)
    }

    @Test
    fun `enqueueDeferred stores a pending run`() = runTest {
        val id = repo.save(workflow("queued"))
        repo.enqueueDeferred(repo.get(id)!!, TriggerEvent.DOC_CREATED, "doc-1", null)

        val pending = repo.listDeferred().single()
        assertEquals(id, pending.workflowId)
        assertEquals("queued", pending.workflowName)
        assertEquals("doc-1", pending.documentId)
        assertEquals(TriggerEvent.DOC_CREATED, pending.trigger)
        assertEquals(1, repo.observeDeferredCount().first())
    }

    @Test
    fun `removeDeferred clears one queued run`() = runTest {
        val id = repo.save(workflow())
        val saved = repo.get(id)!!
        repo.enqueueDeferred(saved, TriggerEvent.DOC_CREATED, "doc-1", null)
        val pending = repo.listDeferred().single()

        repo.removeDeferred(pending.id)

        assertTrue(repo.listDeferred().isEmpty())
        assertEquals(0, repo.observeDeferredCount().first())
    }

    @Test
    fun `deleting a workflow drops its pending runs`() = runTest {
        val id = repo.save(workflow())
        repo.enqueueDeferred(repo.get(id)!!, TriggerEvent.DOC_CREATED, "doc-1", null)

        repo.delete(id)

        assertTrue(repo.listDeferred().isEmpty())
    }

    @Test
    fun `disabling a workflow drops its pending runs`() = runTest {
        val id = repo.save(workflow())
        repo.enqueueDeferred(repo.get(id)!!, TriggerEvent.DOC_CREATED, "doc-1", null)

        repo.setEnabled(id, false)

        assertTrue(repo.listDeferred().isEmpty())
    }
}
