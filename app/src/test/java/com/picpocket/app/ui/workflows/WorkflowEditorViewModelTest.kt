package com.picpocket.app.ui.workflows

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.FakeWorkflowRepository
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.storage.FakeFolderAccess
import com.picpocket.app.domain.workflow.FakeActionRegistry
import com.picpocket.app.domain.workflow.FakeForegroundExecutor
import com.picpocket.app.domain.workflow.RecordingAction
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.CompareOp
import com.picpocket.app.domain.workflow.model.Condition
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.ui.screens.workflows.WorkflowEditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkflowEditorViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
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

    @Test
    fun `new workflow defaults to all selectable triggers`() {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        vm.load(null)
        assertTrue(vm.uiState.value.workflow.name.isNotBlank())
        assertEquals(TriggerEvent.selectable, vm.uiState.value.workflow.triggers)
        assertTrue(TriggerEvent.MANUAL !in vm.uiState.value.workflow.triggers)
    }

    @Test
    fun `setAllTriggers selects and clears`() {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        vm.load(null)
        vm.setAllTriggers(false)
        assertTrue(vm.uiState.value.workflow.triggers.isEmpty())
        vm.setAllTriggers(true)
        assertEquals(TriggerEvent.selectable, vm.uiState.value.workflow.triggers)
    }

    @Test
    fun `root and child build the dependency tree`() {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        vm.load(null)
        vm.addRoot(ActionType.ZIP)
        val rootId = vm.uiState.value.workflow.roots.first().id
        vm.addChild(rootId, ActionType.ENCRYPT)
        assertEquals(1, vm.uiState.value.workflow.roots.size)
        assertEquals(1, vm.uiState.value.workflow.roots.first().children.size)
        assertEquals(ActionType.ENCRYPT, vm.uiState.value.workflow.roots.first().children.first().type)
    }

    @Test
    fun `removeNode removes the node`() {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        vm.load(null)
        vm.addRoot(ActionType.ZIP)
        val rootId = vm.uiState.value.workflow.roots.first().id
        vm.removeNode(rootId)
        assertTrue(vm.uiState.value.workflow.roots.isEmpty())
    }

    @Test
    fun `updateCondition edits a parameter in place`() {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        vm.load(null)
        vm.addCondition(Condition.PageCount(CompareOp.GE, 1))
        vm.updateCondition(0, Condition.PageCount(CompareOp.LE, 7))
        assertEquals(Condition.PageCount(CompareOp.LE, 7), vm.uiState.value.workflow.conditions.single())
    }

    @Test
    fun `save persists the workflow`() = runTest {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        vm.load(null)
        vm.setName("Receipts")
        vm.addRoot(ActionType.ZIP)
        vm.save()
        assertEquals(1, wfRepo.observeWorkflows().first().size)
        assertEquals("Receipts", wfRepo.observeWorkflows().first().first().name)
    }

    @Test
    fun `save removes duplicate conditions`() = runTest {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        vm.load(null)
        vm.setName("w")
        vm.addCondition(Condition.NameMatches("invoice"))
        vm.addCondition(Condition.NameMatches("invoice"))
        vm.addCondition(Condition.OcrTextMatches(" total "))
        vm.addCondition(Condition.OcrTextMatches("total"))
        vm.save()
        val saved = wfRepo.observeWorkflows().first().single()
        assertEquals(2, saved.conditions.size)
        assertEquals(
            listOf(Condition.NameMatches("invoice"), Condition.OcrTextMatches("total")),
            saved.conditions,
        )
    }

    @Test
    fun `save is blocked when a condition is invalid`() = runTest {
        val vm = viewModel(RecordingAction(ActionType.ZIP))
        vm.load(null)
        vm.setName("w")
        vm.addCondition(Condition.NameMatches("(["))
        vm.save()
        assertTrue("nothing persisted", wfRepo.observeWorkflows().first().isEmpty())
        assertTrue(vm.uiState.value.conditionErrors.isNotEmpty())
        assertEquals("Invalid regex pattern", vm.uiState.value.conditionErrors[0])
    }

    @Test
    fun `runNow executes an action and records a run`() = runTest {
        val action = RecordingAction(ActionType.ZIP)
        val vm = viewModel(action)
        docRepo.seedDocument("doc-1", "Receipt")
        vm.load(null)
        vm.addRoot(ActionType.ZIP)
        vm.save()
        vm.runNow()
        assertEquals(1, action.runs)
        assertEquals(1, wfRepo.observeRuns(1L).first().size)
    }

    @Test
    fun `save is blocked when a save-to-folder action has no folder`() = runTest {
        val vm = viewModel(RecordingAction(ActionType.SAVE_TO_FOLDER))
        vm.load(null)
        vm.setName("w")
        vm.addRoot(ActionType.SAVE_TO_FOLDER)
        vm.save()
        assertTrue("nothing persisted", wfRepo.observeWorkflows().first().isEmpty())
        assertTrue(vm.uiState.value.nodeErrors.isNotEmpty())
    }

    @Test
    fun `save proceeds when the folder grant is held`() = runTest {        val uri = "content://com.example.documents/tree/primary%3APictures"
        folderAccess.persist(uri)
        val vm = viewModel(RecordingAction(ActionType.SAVE_TO_FOLDER))
        vm.load(null)
        vm.setName("w")
        vm.addRoot(ActionType.SAVE_TO_FOLDER)
        val nodeId = vm.uiState.value.workflow.roots.first().id
        vm.setNodeParams(nodeId, ActionParams.SaveToFolder(uri, "Pictures"))
        vm.save()
        assertEquals(1, wfRepo.observeWorkflows().first().size)
    }

    @Test
    fun `onFolderPicked keeps the grant and stores the folder name`() {
        val uri = "content://com.example.documents/tree/primary%3APictures"
        folderAccess.names[uri] = "Pictures"
        val vm = viewModel(RecordingAction(ActionType.SAVE_TO_FOLDER))
        vm.load(null)
        vm.addRoot(ActionType.SAVE_TO_FOLDER)
        val nodeId = vm.uiState.value.workflow.roots.first().id
        vm.onFolderPicked(nodeId, Uri.parse(uri))
        val params = vm.uiState.value.workflow.roots.first().params as ActionParams.SaveToFolder
        assertEquals("Pictures", params.displayName)
        assertEquals(uri, params.folderUri)
        assertTrue(folderAccess.isPersisted(Uri.parse(uri)))
    }

    @Test
    fun `save is blocked when delete is a top-level action`() = runTest {
        val vm = viewModel(RecordingAction(ActionType.DELETE))
        vm.load(null)
        vm.setName("w")
        // The editor hides delete from the "add action" menu, but a rogue rule
        // must still be rejected on save.
        vm.addRoot(ActionType.DELETE)
        vm.save()
        assertTrue("nothing persisted", wfRepo.observeWorkflows().first().isEmpty())
        assertTrue(vm.uiState.value.nodeErrors.isNotEmpty())
    }
}
