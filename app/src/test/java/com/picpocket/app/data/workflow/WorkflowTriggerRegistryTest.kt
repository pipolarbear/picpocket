package com.picpocket.app.data.workflow

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.FakeWorkflowRepository
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.storage.FakeFolderAccess
import com.picpocket.app.domain.workflow.FakeActionRegistry
import com.picpocket.app.domain.workflow.FakeForegroundExecutor
import com.picpocket.app.domain.workflow.FakeNetworkMonitor
import com.picpocket.app.domain.workflow.RecordingAction
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionParams
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

    private fun registry(
        actions: List<RecordingAction>,
        scope: CoroutineScope,
        network: FakeNetworkMonitor = FakeNetworkMonitor(),
        folderAccess: FakeFolderAccess = FakeFolderAccess(),
        activityBus: WorkflowActivityBus = WorkflowActivityBus(),
    ): WorkflowTriggerRegistry {
        val engine = WorkflowEngine(
            app = app,
            repository = repo,
            workflowRepository = workflowRepo,
            pdfGenerator = FakePdfGenerator(),
            cipher = ArtifactCipher(),
            foreground = FakeForegroundExecutor(),
            registry = FakeActionRegistry(actions.associateBy { it.type }),
            folderAccess = folderAccess,
            network = network,
        )
        return WorkflowTriggerRegistry(
            bus = bus,
            documentRepository = repo,
            workflowRepository = workflowRepo,
            engine = engine,
            activityBus = activityBus,
            notifier = WorkflowRunNotifier(app),
            scope = scope,
        )
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

    @Test
    fun `an offline network run is recorded as deferred and queued`() = runTest {
        repo.seedDocument("doc-1", "Receipt")
        val network = FakeNetworkMonitor(online = false)
        val folderAccess = FakeFolderAccess().apply {
            remoteAuthorities.add("com.google.android.apps.docs.storage")
        }
        val save = RecordingAction(ActionType.SAVE_TO_FOLDER, needsNetwork = true)
        workflowRepo.save(
            Workflow(
                name = "receipts",
                triggers = listOf(TriggerEvent.DOC_CREATED),
                roots = listOf(
                    ActionNode(
                        "save",
                        ActionType.SAVE_TO_FOLDER,
                        params = ActionParams.SaveToFolder(
                            "content://com.google.android.apps.docs.storage/tree/drive",
                            "Drive",
                        ),
                    ),
                ),
            ),
        )

        registry(listOf(save), this, network, folderAccess)
            .handle(DocumentEvent(TriggerEvent.DOC_CREATED, "doc-1"))

        assertEquals(0, save.runs)
        assertEquals(1, workflowRepo.listDeferred().size)
        val run = workflowRepo.observeRuns(1L).first().single()
        assertEquals(RunStatus.DEFERRED, run.status)
        assertTrue(run.nodes.all { it.status == NodeStatus.DEFERRED })
    }

    @Test
    fun `a failed run publishes its reason for the UI`() = runTest {
        repo.seedDocument("doc-1", "Receipt")
        val activityBus = WorkflowActivityBus()
        val failing = RecordingAction(ActionType.ENCRYPT, result = ActionResult.Failure("boom"))
        workflowRepo.save(
            Workflow(
                name = "receipts",
                triggers = listOf(TriggerEvent.DOC_CREATED),
                roots = listOf(ActionNode("bad", ActionType.ENCRYPT)),
            ),
        )

        registry(listOf(failing), this, activityBus = activityBus)
            .handle(DocumentEvent(TriggerEvent.DOC_CREATED, "doc-1"))

        val activity = activityBus.lastFor("doc-1")
        assertEquals(RunStatus.FAILURE, activity?.status)
        assertEquals("boom", activity?.failureReason)
        assertEquals("receipts", activity?.workflowName)
    }
}
