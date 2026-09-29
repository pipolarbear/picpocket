package com.picpocket.app.data.workflow

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.FakeWorkflowRepository
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.workflow.FakeActionRegistry
import com.picpocket.app.domain.workflow.FakeForegroundExecutor
import com.picpocket.app.domain.workflow.RecordingAction
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.NodeStatus
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkflowTriggerRegistryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = FakeDocumentRepository()
    private val workflowRepo = FakeWorkflowRepository()
    private val bus = DocumentEventBus()

    private fun registry(actions: List<RecordingAction>, scope: CoroutineScope): WorkflowTriggerRegistry {
        val engine = WorkflowEngine(
            app = app,
            repository = repo,
            pdfGenerator = FakePdfGenerator(),
            cipher = ArtifactCipher(),
            foreground = FakeForegroundExecutor(),
            registry = FakeActionRegistry(actions.associateBy { it.type }),
        )
        return WorkflowTriggerRegistry(bus, repo, workflowRepo, engine, scope)
    }

    private fun registry(action: RecordingAction, scope: CoroutineScope) =
        registry(listOf(action), scope)

    @Test
    fun `a matching event runs the workflow and records a run`() = runTest {
        repo.seedDocument("doc-1", "Receipt")
        val action = RecordingAction(ActionType.ZIP)
        workflowRepo.save(
            Workflow(
                name = "w",
                triggers = listOf(TriggerEvent.DOC_CREATED),
                roots = listOf(ActionNode("a", ActionType.ZIP)),
            ),
        )
        registry(action, this).handle(DocumentEvent(TriggerEvent.DOC_CREATED, "doc-1"))

        assertEquals(1, action.runs)
        assertEquals(1, workflowRepo.observeRuns(1L).first().size)
    }

    @Test
    fun `events produced during a run are ignored`() = runTest {
        repo.seedDocument("doc-1", "Receipt")
        val action = RecordingAction(ActionType.ZIP)
        workflowRepo.save(
            Workflow(
                name = "w",
                triggers = listOf(TriggerEvent.DOC_CREATED),
                roots = listOf(ActionNode("a", ActionType.ZIP)),
            ),
        )
        registry(action, this).handle(DocumentEvent(TriggerEvent.DOC_CREATED, "doc-1", fromWorkflow = true))

        assertEquals(0, action.runs)
    }

    @Test
    fun `an unmatched trigger does not run`() = runTest {
        repo.seedDocument("doc-1", "Receipt")
        val action = RecordingAction(ActionType.ZIP)
        workflowRepo.save(
            Workflow(
                name = "w",
                triggers = listOf(TriggerEvent.RENAMED),
                roots = listOf(ActionNode("a", ActionType.ZIP)),
            ),
        )
        registry(action, this).handle(DocumentEvent(TriggerEvent.DOC_CREATED, "doc-1"))

        assertEquals(0, action.runs)
    }

    @Test
    fun `a two-branch forest records both branches`() = runTest {
        repo.seedDocument("doc-1", "Receipt")
        val actions = listOf(
            RecordingAction(ActionType.ENCRYPT),
            RecordingAction(ActionType.ZIP),
            RecordingAction(ActionType.SAVE_TO_FOLDER),
            RecordingAction(ActionType.SEND_TO_APP),
            RecordingAction(ActionType.NOTIFY),
        )
        workflowRepo.save(
            Workflow(
                name = "w",
                triggers = listOf(TriggerEvent.DOC_CREATED),
                roots = listOf(
                    ActionNode(
                        "e", ActionType.ENCRYPT,
                        children = listOf(
                            ActionNode("z", ActionType.ZIP, children = listOf(ActionNode("s", ActionType.SAVE_TO_FOLDER))),
                        ),
                    ),
                    ActionNode("send", ActionType.SEND_TO_APP, children = listOf(ActionNode("n", ActionType.NOTIFY))),
                ),
            ),
        )

        registry(actions, this).handle(DocumentEvent(TriggerEvent.DOC_CREATED, "doc-1"))

        val run = workflowRepo.observeRuns(1L).first().single()
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(setOf("e", "z", "s", "send", "n"), run.nodes.map { it.nodeId }.toSet())
    }

    @Test
    fun `a failing branch still completes the independent branch and records the reason`() = runTest {
        repo.seedDocument("doc-1", "Receipt")
        val failing = RecordingAction(ActionType.ENCRYPT, result = ActionResult.Failure("boom"))
        val independent = RecordingAction(ActionType.ZIP)
        workflowRepo.save(
            Workflow(
                name = "w",
                triggers = listOf(TriggerEvent.DOC_CREATED),
                roots = listOf(
                    ActionNode("bad", ActionType.ENCRYPT),
                    ActionNode("good", ActionType.ZIP),
                ),
            ),
        )

        registry(listOf(failing, independent), this)
            .handle(DocumentEvent(TriggerEvent.DOC_CREATED, "doc-1"))

        val run = workflowRepo.observeRuns(1L).first().single()
        assertEquals(RunStatus.PARTIAL_FAILURE, run.status)
        assertTrue(run.nodes.any { it.status == NodeStatus.FAILED && it.reason == "boom" })
        assertTrue(run.nodes.any { it.nodeId == "good" && it.status == NodeStatus.SUCCESS })
    }
}
