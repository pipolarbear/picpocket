package com.picpocket.app.ui

import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.FakeWorkflowRepository
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.Condition
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.ui.screens.tags.TagManagementViewModel
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class TagManagementViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var workflowRepository: FakeWorkflowRepository
    private lateinit var viewModel: TagManagementViewModel

    @Before
    fun setUp() {
        repo = FakeDocumentRepository()
        workflowRepository = FakeWorkflowRepository()
        viewModel = TagManagementViewModel(repo, workflowRepository)
    }

    @Test
    fun `initial state has empty tags`() {
        assertTrue(viewModel.uiState.value.allTags.isEmpty())
    }

    @Test
    fun `createTag adds to list`() = runTest {
        repo.createTag("Work")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.allTags.size)
        assertEquals("Work", viewModel.uiState.value.allTags[0].name)
    }

    @Test
    fun `search query filters tags`() = runTest {
        repo.createTag("Important")
        repo.createTag("Personal")
        repo.createTag("Work")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("imp")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.allTags.size)
        assertEquals("Important", viewModel.uiState.value.allTags[0].name)
    }

    @Test
    fun `clear search shows all tags`() = runTest {
        repo.createTag("One")
        repo.createTag("Two")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("One")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.allTags.size)

        viewModel.setSearchQuery("")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.allTags.size)
    }

    @Test
    fun `createTag via viewModel`() = runTest {
        viewModel.createTag("Work")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.allTags.size)
        assertEquals("Work", viewModel.uiState.value.allTags[0].name)
    }

    @Test
    fun `deleteSelected through confirmation flow`() = runTest {
        val id1 = repo.createTag("Tag1")
        val id2 = repo.createTag("Tag2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.enterSelectionMode(id1)
        viewModel.toggleSelection(id2)
        viewModel.showDeleteConfirmation()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)
        viewModel.confirmDelete()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.allTags.size)
        assertFalse(viewModel.uiState.value.selectionMode)
    }

    @Test
    fun `delete single tag through confirmation flow`() = runTest {
        repo.createTag("Tag1")
        repo.createTag("Tag2")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val tagId = viewModel.uiState.value.allTags[0].id
        viewModel.showDeleteConfirmationForTag(tagId)
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)
        assertEquals(tagId, viewModel.uiState.value.pendingDeleteTagId)
        viewModel.confirmDelete()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.allTags.size)
    }

    @Test
    fun `hideDeleteConfirmation clears state`() {
        viewModel.showDeleteConfirmationForTag(1L)
        viewModel.hideDeleteConfirmation()
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
    }

    @Test
    fun `enterSelectionMode activates selection`() {
        viewModel.enterSelectionMode(1L)
        assertTrue(viewModel.uiState.value.selectionMode)
        assertEquals(setOf(1L), viewModel.uiState.value.selectedTagIds)
    }

    @Test
    fun `toggleSelection adds and removes`() {
        viewModel.enterSelectionMode(1L)
        viewModel.toggleSelection(2L)
        assertTrue(2L in viewModel.uiState.value.selectedTagIds)
        viewModel.toggleSelection(1L)
        assertFalse(1L in viewModel.uiState.value.selectedTagIds)
    }

    @Test
    fun `exitSelectionMode clears state`() {
        viewModel.enterSelectionMode(1L)
        viewModel.exitSelectionMode()
        assertFalse(viewModel.uiState.value.selectionMode)
        assertTrue(viewModel.uiState.value.selectedTagIds.isEmpty())
    }

    @Test
    fun `delete is blocked while a workflow references the tag`() = runTest {
        val tagId = repo.createTag("Receipts")
        workflowRepository.save(
            Workflow(
                name = "w",
                triggers = listOf(TriggerEvent.DOC_CREATED),
                conditions = listOf(Condition.HasTag(tagId)),
                roots = listOf(ActionNode("a", ActionType.ZIP)),
            ),
        )
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showDeleteConfirmationForTag(tagId)
        viewModel.confirmDelete()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showDeleteBlocked)
        assertEquals("tag must not be removed", 1, viewModel.uiState.value.allTags.size)
    }

    @Test
    fun `delete proceeds when no workflow references the tag`() = runTest {
        val tagId = repo.createTag("Receipts")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showDeleteConfirmationForTag(tagId)
        viewModel.confirmDelete()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showDeleteBlocked)
        assertTrue(viewModel.uiState.value.allTags.isEmpty())
    }

    @Test
    fun `rename to an existing tag name is refused`() = runTest {
        repo.createTag("One")
        repo.createTag("Two")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val second = viewModel.uiState.value.allTags.first { it.name == "Two" }
        viewModel.startEditing(second.id)
        viewModel.updateEditingName("One")
        viewModel.saveEdit()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertTrue("a message must be shown", viewModel.uiState.value.message != null)
        assertEquals(
            "name must be unchanged",
            "Two",
            viewModel.uiState.value.allTags.first { it.id == second.id }.name,
        )
    }
}
