package com.picpocket.app.ui.workflows

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.data.FakeWorkflowRepository
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.ui.screens.workflows.WorkflowsScreen
import com.picpocket.app.ui.screens.workflows.WorkflowsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkflowsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val repo = FakeWorkflowRepository()

    @Test
    fun duplicateAddsACopy() {
        runBlocking {
            repo.save(
                Workflow(
                    name = "One",
                    triggers = listOf(TriggerEvent.DOC_CREATED),
                    roots = listOf(ActionNode("a", ActionType.ZIP)),
                ),
            )
        }
        val viewModel = WorkflowsViewModel(repo)
        composeRule.setContent {
            MaterialTheme {
                WorkflowsScreen(onNavigateBack = {}, viewModel = viewModel)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.workflows.isNotEmpty() }

        composeRule.onNodeWithTag("duplicate_workflow").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.workflows.size == 2 }
        composeRule.onNodeWithText("One copy").assertIsDisplayed()
    }

    @Test
    fun deleteConfirmsAndUndoRestores() {
        runBlocking {
            repo.save(
                Workflow(
                    name = "Gone",
                    triggers = listOf(TriggerEvent.DOC_CREATED),
                    roots = listOf(ActionNode("a", ActionType.ZIP)),
                ),
            )
        }
        val viewModel = WorkflowsViewModel(repo)
        composeRule.setContent {
            MaterialTheme {
                WorkflowsScreen(onNavigateBack = {}, viewModel = viewModel)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.workflows.isNotEmpty() }

        composeRule.onNodeWithTag("delete_workflow").performClick()
        composeRule.onNodeWithText("Delete workflow?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.workflows.isEmpty() }

        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.workflows.isNotEmpty() }
        composeRule.onNodeWithText("Gone").assertIsDisplayed()
    }
}
