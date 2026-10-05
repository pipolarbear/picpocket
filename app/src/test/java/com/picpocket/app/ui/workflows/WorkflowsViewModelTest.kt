package com.picpocket.app.ui.workflows

import com.picpocket.app.data.FakeWorkflowRepository
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.ui.screens.workflows.WorkflowsViewModel
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class WorkflowsViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repository: FakeWorkflowRepository
    private lateinit var viewModel: WorkflowsViewModel

    @Before
    fun setUp() {
        repository = FakeWorkflowRepository()
        viewModel = WorkflowsViewModel(repository)
    }

    private fun workflow(name: String) = Workflow(
        name = name,
        triggers = listOf(TriggerEvent.DOC_CREATED),
        roots = listOf(ActionNode("a", ActionType.ZIP)),
    )

    @Test
    fun `lists workflows from the repository`() = runTest {
        repository.save(workflow("One"))
        repository.save(workflow("Two"))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.workflows.size)
    }

    @Test
    fun `delete removes a workflow after confirmation`() = runTest {
        repository.save(workflow("One"))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val stored = viewModel.uiState.value.workflows.single()
        viewModel.requestDelete(stored)
        assertTrue(viewModel.uiState.value.pendingDelete != null)
        viewModel.delete()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.workflows.isEmpty())
    }

    @Test
    fun `undo delete restores the workflow`() = runTest {
        repository.save(workflow("One"))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val stored = viewModel.uiState.value.workflows.single()
        viewModel.requestDelete(stored)
        viewModel.delete()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.workflows.isEmpty())

        viewModel.undoDelete()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.workflows.isNotEmpty())
    }

    @Test
    fun `clone adds a copy`() = runTest {
        val id = repository.save(workflow("One"))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.clone(id)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val names = viewModel.uiState.value.workflows.map { it.name }
        assertEquals(2, names.size)
        assertTrue("copy present", names.contains("One copy"))
    }

    @Test
    fun `setEnabled toggles the workflow`() = runTest {
        val id = repository.save(workflow("One"))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setEnabled(id, false)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.workflows.single().enabled)
    }

    @Test
    fun `openEditor sets editor state`() {
        viewModel.openEditor(7L)
        assertTrue(viewModel.uiState.value.showEditor)
        assertEquals(7L, viewModel.uiState.value.editorWorkflowId)
        viewModel.closeEditor()
        assertFalse(viewModel.uiState.value.showEditor)
    }
}
