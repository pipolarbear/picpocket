package com.picpocket.app.ui.workflows

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.FakeWorkflowRepository
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.storage.FakeFolderAccess
import com.picpocket.app.domain.workflow.FakeActionRegistry
import com.picpocket.app.domain.workflow.FakeForegroundExecutor
import com.picpocket.app.domain.workflow.RecordingAction
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.CompareOp
import com.picpocket.app.domain.workflow.model.Condition
import com.picpocket.app.domain.workflow.model.NodeResult
import com.picpocket.app.domain.workflow.model.NodeStatus
import com.picpocket.app.domain.workflow.model.RunResult
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.ui.screens.workflows.WorkflowEditorContent
import com.picpocket.app.ui.screens.workflows.WorkflowEditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkflowEditorContentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val docRepo = FakeDocumentRepository()
    private val wfRepo = FakeWorkflowRepository()
    private val folderAccess = FakeFolderAccess()

    private fun viewModel(vararg actions: RecordingAction): WorkflowEditorViewModel {
        val engine = WorkflowEngine(
            app = app,
            repository = docRepo,
            pdfGenerator = FakePdfGenerator(),
            cipher = ArtifactCipher(),
            foreground = FakeForegroundExecutor(),
            registry = FakeActionRegistry(actions.associateBy { it.type }),
        )
        return WorkflowEditorViewModel(wfRepo, docRepo, engine, folderAccess)
    }

    private fun render(viewModel: WorkflowEditorViewModel, workflowId: Long? = null) {
        composeRule.setContent {
            MaterialTheme {
                WorkflowEditorContent(workflowId = workflowId, viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun addActionAndDependentStep() {
        render(viewModel(RecordingAction(ActionType.ZIP), RecordingAction(ActionType.ENCRYPT)))

        composeRule.onNodeWithTag("add_action").performScrollTo().performClick()
        composeRule.onNodeWithText(ActionType.ZIP.label).performClick()
        waitForText(ActionType.ZIP.label)

        composeRule.onAllNodesWithTag("add_step")[0].performScrollTo().performClick()
        composeRule.onNodeWithText(ActionType.ENCRYPT.label).performClick()
        waitForText(ActionType.ENCRYPT.label)

        composeRule.onNodeWithText("Passphrase").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun triggerTreeSelectsAndClearsAll() {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        render(vm)

        assertTrue(vm.uiState.value.workflow.triggers.isEmpty())
        composeRule.onNodeWithTag("trigger_all").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(TriggerEvent.selectable, vm.uiState.value.workflow.triggers)
        composeRule.onNodeWithTag("trigger_all").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue("parent click clears all", vm.uiState.value.workflow.triggers.isEmpty())
    }

    @Test
    fun conditionParameterCanBeEdited() {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        render(vm)

        composeRule.onNodeWithText("+ Add condition").performScrollTo().performClick()
        composeRule.onNodeWithText("Page count").performClick()
        waitForTag("page_count_value")

        composeRule.onNodeWithTag("page_count_value").performScrollTo().performTextReplacement("3")
        composeRule.waitForIdle()

        assertEquals(
            Condition.PageCount(CompareOp.GE, 3),
            vm.uiState.value.workflow.conditions.single(),
        )
    }

    @Test
    fun saveRemovesDuplicateConditions() {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        render(vm)
        vm.setName("w")

        composeRule.onNodeWithText("+ Add condition").performScrollTo().performClick()
        composeRule.onNodeWithText("Name matches").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("+ Add condition").performScrollTo().performClick()
        composeRule.onNodeWithText("Name matches").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()

        val saved = runBlocking { wfRepo.observeWorkflows().first() }.single()
        assertEquals("duplicate conditions collapsed", 1, saved.conditions.size)
    }

    @Test
    fun runNowExecutesTheForest() {
        docRepo.seedDocument("doc-1", "Receipt")
        val action = RecordingAction(ActionType.ZIP)
        render(viewModel(action))

        composeRule.onNodeWithTag("add_action").performScrollTo().performClick()
        composeRule.onNodeWithText(ActionType.ZIP.label).performClick()
        waitForText(ActionType.ZIP.label)

        composeRule.onNodeWithText("Run now").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { action.runs == 1 }
    }

    @Test
    fun tagConditionShowsItsTypeAndName() {
        runBlocking { docRepo.createTag("receipts") }
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        render(vm)
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.uiState.value.allTags.isNotEmpty() }

        composeRule.onNodeWithText("+ Add condition").performScrollTo().performClick()
        composeRule.onNodeWithText("Has tag").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Tag:").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("receipts").assertIsDisplayed()
    }

    @Test
    fun historyShowsFailureAndClearEmptiesIt() {        val workflowId = runBlocking {
            wfRepo.save(
                Workflow(
                    name = "w",
                    triggers = listOf(TriggerEvent.DOC_CREATED),
                    roots = listOf(ActionNode("a", ActionType.ENCRYPT)),
                ),
            )
        }
        runBlocking {
            wfRepo.recordRun(
                Workflow(id = workflowId, name = "w"),
                TriggerEvent.DOC_CREATED,
                0L,
                RunResult(
                    status = RunStatus.PARTIAL_FAILURE,
                    nodes = listOf(NodeResult("a", ActionType.ENCRYPT, NodeStatus.FAILED, "boom")),
                ),
            )
        }

        render(viewModel(RecordingAction(ActionType.ENCRYPT)), workflowId = workflowId)
        waitForText("✗ Encrypt: boom")

        composeRule.onNodeWithText("Clear").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { wfRepo.observeRuns(workflowId).first() }.isEmpty()
        }
    }

    @Test
    fun deleteIsOnlyOfferedAsATerminalStep() {
        render(
            viewModel(
                RecordingAction(ActionType.ZIP),
                RecordingAction(ActionType.DELETE),
                RecordingAction(ActionType.NOTIFY),
            ),
        )

        // The add-action (root) menu must not offer delete.
        composeRule.onNodeWithTag("add_action").performScrollTo().performClick()
        assertTrue(
            "delete must not be a top-level action",
            composeRule.onAllNodesWithText(ActionType.DELETE.label).fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithText(ActionType.ZIP.label).performClick()
        waitForText(ActionType.ZIP.label)

        // An add-step menu does offer delete.
        composeRule.onAllNodesWithTag("add_step")[0].performScrollTo().performClick()
        composeRule.onNodeWithText(ActionType.DELETE.label).performClick()
        waitForText(ActionType.DELETE.label)

        // delete is terminal: only the zip node still has an add-step button.
        assertEquals(1, composeRule.onAllNodesWithTag("add_step").fetchSemanticsNodes().size)
    }
}
