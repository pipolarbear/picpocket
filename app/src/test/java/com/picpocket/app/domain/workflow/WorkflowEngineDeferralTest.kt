package com.picpocket.app.domain.workflow

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.FakeWorkflowRepository
import com.picpocket.app.data.model.Document
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.storage.FakeFolderAccess
import com.picpocket.app.domain.workflow.action.DeleteAction
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkflowEngineDeferralTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = FakeDocumentRepository()
    private val workflowRepo = FakeWorkflowRepository()
    private val network = FakeNetworkMonitor()
    private val folderAccess = FakeFolderAccess()

    private val document = Document(
        id = "doc-1",
        name = "Receipt 2026",
        createdAt = 0,
        updatedAt = 0,
        pageCount = 2,
    )

    private fun engine(vararg actions: RecordingAction): WorkflowEngine = WorkflowEngine(
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

    private fun saveToFolder(uri: String = REMOTE_FOLDER) = ActionNode(
        id = "save",
        type = ActionType.SAVE_TO_FOLDER,
        params = ActionParams.SaveToFolder(uri, "Drive"),
    )

    @Test
    fun `offline run with a remote folder is deferred and queued`() = runTest {
        network.online = false
        folderAccess.remoteAuthorities.add(REMOTE_AUTHORITY)
        val save = RecordingAction(ActionType.SAVE_TO_FOLDER, needsNetwork = true)
        val workflow = Workflow(
            id = 1,
            name = "receipts",
            triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(saveToFolder()),
        )

        val result = engine(save).run(workflow, TriggerEvent.DOC_CREATED, document)

        assertEquals(RunStatus.DEFERRED, result.status)
        assertEquals(0, save.runs)
        assertTrue(result.nodes.all { it.status == NodeStatus.DEFERRED })
        assertEquals(1, workflowRepo.listDeferred().size)
    }

    @Test
    fun `offline run with a share is deferred and queued`() = runTest {
        network.online = false
        val send = RecordingAction(ActionType.SEND_TO_APP, needsNetwork = true)
        val workflow = Workflow(
            id = 1,
            name = "share",
            triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(ActionNode("send", ActionType.SEND_TO_APP)),
        )

        val result = engine(send).run(workflow, TriggerEvent.DOC_CREATED, document)

        assertEquals(RunStatus.DEFERRED, result.status)
        assertEquals(0, send.runs)
        assertEquals(1, workflowRepo.listDeferred().size)
    }

    @Test
    fun `offline run with only local actions still executes`() = runTest {
        network.online = false
        // The folder is local, so the save does not need the network.
        val save = RecordingAction(ActionType.SAVE_TO_FOLDER)
        val zip = RecordingAction(ActionType.ZIP)
        val workflow = Workflow(
            id = 1,
            name = "local",
            triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(ActionNode("zip", ActionType.ZIP), saveToFolder(LOCAL_FOLDER)),
        )

        val result = engine(zip, save).run(workflow, TriggerEvent.DOC_CREATED, document)

        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(1, zip.runs)
        assertEquals(1, save.runs)
        assertTrue(workflowRepo.listDeferred().isEmpty())
    }

    @Test
    fun `offline deferral keeps a delete child from running`() = runTest {
        network.online = false
        folderAccess.remoteAuthorities.add(REMOTE_AUTHORITY)
        repo.seedDocument("doc-1", "Receipt 2026")
        val save = RecordingAction(ActionType.SAVE_TO_FOLDER, needsNetwork = true)
        val workflow = Workflow(
            id = 1,
            name = "save then delete",
            triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(saveToFolder().copy(children = listOf(ActionNode("del", ActionType.DELETE)))),
        )

        engine(save).run(workflow, TriggerEvent.DOC_CREATED, document)

        assertEquals(0, save.runs)
        assertTrue("document must still exist", repo.getDocument("doc-1").isSuccess)
    }

    @Test
    fun `online run with a remote folder executes and is not queued`() = runTest {
        network.online = true
        folderAccess.remoteAuthorities.add(REMOTE_AUTHORITY)
        val save = RecordingAction(ActionType.SAVE_TO_FOLDER, needsNetwork = true)
        val workflow = Workflow(
            id = 1,
            name = "receipts",
            triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(saveToFolder()),
        )

        val result = engine(save).run(workflow, TriggerEvent.DOC_CREATED, document)

        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(1, save.runs)
        assertTrue(workflowRepo.listDeferred().isEmpty())
    }

    @Test
    fun `delete is skipped when an earlier branch failed`() = runTest {
        repo.seedDocument("doc-1", "Receipt 2026")
        val zip = RecordingAction(ActionType.ZIP)
        val failing = RecordingAction(ActionType.ENCRYPT, result = ActionResult.Failure("boom"))
        val workflow = Workflow(
            id = 1,
            name = "keep on failure",
            triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(
                ActionNode("zip", ActionType.ZIP, children = listOf(ActionNode("del", ActionType.DELETE))),
                ActionNode("bad", ActionType.ENCRYPT),
            ),
        )

        val result = WorkflowEngine(
            app = app,
            repository = repo,
            workflowRepository = workflowRepo,
            pdfGenerator = FakePdfGenerator(),
            cipher = ArtifactCipher(),
            foreground = FakeForegroundExecutor(),
            registry = FakeActionRegistry(
                mapOf(
                    ActionType.ZIP to zip,
                    ActionType.ENCRYPT to failing,
                    ActionType.DELETE to DeleteAction(),
                ),
            ),
            folderAccess = folderAccess,
            network = network,
        ).run(workflow, TriggerEvent.DOC_CREATED, document)

        assertTrue("document must be kept", repo.getDocument("doc-1").isSuccess)
        val delete = result.nodes.single { it.nodeId == "del" }
        assertEquals(NodeStatus.SKIPPED, delete.status)
        assertTrue(delete.reason!!.contains("Kept the document"))
    }

    @Test
    fun `an unsaved workflow is reported deferred but not queued`() = runTest {
        network.online = false
        val save = RecordingAction(ActionType.SAVE_TO_FOLDER, needsNetwork = true)
        val workflow = Workflow(
            name = "unsaved",
            triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(saveToFolder()),
        )

        val result = engine(save).run(workflow, TriggerEvent.DOC_CREATED, document)

        assertEquals(RunStatus.DEFERRED, result.status)
        assertTrue(workflowRepo.listDeferred().isEmpty())
    }

    private companion object {
        const val REMOTE_AUTHORITY = "com.google.android.apps.docs.storage"
        const val REMOTE_FOLDER = "content://com.google.android.apps.docs.storage/tree/drive"
        const val LOCAL_FOLDER = "content://com.android.externalstorage.documents/tree/primary%3APictures"
    }
}
