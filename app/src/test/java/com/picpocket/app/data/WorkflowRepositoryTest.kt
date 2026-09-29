package com.picpocket.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.local.PicPocketDatabase
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.data.repository.WorkflowRepositoryImpl
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
        repo = WorkflowRepositoryImpl(db.workflowDao(), db.workflowRunDao())
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
        repo.recordRun(workflow(), TriggerEvent.DOC_CREATED, 0L, RunResult(RunStatus.SUCCESS, emptyList()))
        repo.delete(id)
        assertNull(repo.get(id))
        assertEquals(0, repo.observeRuns(id).first().size)
    }

    @Test
    fun `history is pruned to the limit`() = runTest {
        val id = repo.save(workflow())
        val saved = workflow().copy(id = id)
        repeat(WorkflowRepository.HISTORY_LIMIT + 5) { i ->
            repo.recordRun(saved, TriggerEvent.DOC_CREATED, i.toLong(), RunResult(RunStatus.SUCCESS, emptyList()))
        }
        assertEquals(WorkflowRepository.HISTORY_LIMIT, repo.observeRuns(id).first().size)
    }

    @Test
    fun `clearHistory empties the runs`() = runTest {
        val id = repo.save(workflow())
        repo.recordRun(workflow().copy(id = id), TriggerEvent.DOC_CREATED, 0L, RunResult(RunStatus.FAILURE, emptyList()))
        repo.clearHistory(id)
        assertEquals(0, repo.observeRuns(id).first().size)
    }
}
