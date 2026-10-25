package com.picpocket.app.domain.workflow

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.FakeWorkflowRepository
import com.picpocket.app.data.workflow.WorkflowActivityBus
import com.picpocket.app.data.workflow.WorkflowRunNotifier
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.storage.FakeFolderAccess
import com.picpocket.app.domain.workflow.action.Execution
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.domain.workflow.queue.DeferredRunProcessor
import com.picpocket.app.domain.workflow.queue.DrainOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeferredRunProcessorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val docRepo = FakeDocumentRepository()
    private val wfRepo = FakeWorkflowRepository()
    private val network = FakeNetworkMonitor()
    private val folderAccess = FakeFolderAccess()
    private val foreground = FakeForegroundExecutor()

    private fun processor(vararg actions: RecordingAction): DeferredRunProcessor {
        val registry = FakeActionRegistry(actions.associateBy { it.type })
        val engine = WorkflowEngine(
            app = app,
            repository = docRepo,
            workflowRepository = wfRepo,
            pdfGenerator = FakePdfGenerator(),
            cipher = ArtifactCipher(),
            foreground = foreground,
            registry = registry,
            folderAccess = folderAccess,
            network = network,
        )
        return DeferredRunProcessor(
            workflowRepository = wfRepo,
            documentRepository = docRepo,
            engine = engine,
            registry = registry,
            foreground = foreground,
            activityBus = WorkflowActivityBus(),
            notifier = WorkflowRunNotifier(app),
            context = app,
        )
    }

    private suspend fun save(type: ActionType): Workflow {
        val id = wfRepo.save(
            Workflow(
                name = "w",
                triggers = listOf(TriggerEvent.DOC_CREATED),
                roots = listOf(ActionNode("a", type)),
            ),
        )
        return wfRepo.get(id)!!
    }

    @Test
    fun `a queued run executes when online and is removed`() = runTest {
        docRepo.seedDocument("doc-1", "Receipt")
        val workflow = save(ActionType.ZIP)
        wfRepo.enqueueDeferred(workflow, TriggerEvent.DOC_CREATED, "doc-1", "Receipt")
        val zip = RecordingAction(ActionType.ZIP)

        assertEquals(DrainOutcome.DONE, processor(zip).process())

        assertEquals(1, zip.runs)
        assertTrue(wfRepo.listDeferred().isEmpty())
        val run = wfRepo.observeRuns(workflow.id).first().single()
        assertEquals(RunStatus.SUCCESS, run.status)
    }

    @Test
    fun `a queued run stays while the device is still offline`() = runTest {
        docRepo.seedDocument("doc-1", "Receipt")
        val workflow = save(ActionType.SAVE_TO_FOLDER)
        network.online = false
        wfRepo.enqueueDeferred(workflow, TriggerEvent.DOC_CREATED, "doc-1", "Receipt")
        val save = RecordingAction(ActionType.SAVE_TO_FOLDER, needsNetwork = true)

        assertEquals(DrainOutcome.RETRY, processor(save).process())

        assertEquals(0, save.runs)
        assertEquals(1, wfRepo.listDeferred().size)
        assertTrue(wfRepo.observeRuns(workflow.id).first().isEmpty())
    }

    @Test
    fun `a queued run is cleared when its document is gone`() = runTest {
        val workflow = save(ActionType.ZIP)
        wfRepo.enqueueDeferred(workflow, TriggerEvent.DOC_CREATED, "missing", null)

        assertEquals(DrainOutcome.DONE, processor(RecordingAction(ActionType.ZIP)).process())

        assertTrue(wfRepo.listDeferred().isEmpty())
        assertEquals(RunStatus.FAILURE, wfRepo.observeRuns(workflow.id).first().single().status)
    }

    @Test
    fun `a queued run is dropped when its workflow is gone`() = runTest {
        val ghost = Workflow(id = 99, name = "gone", roots = listOf(ActionNode("a", ActionType.ZIP)))
        wfRepo.enqueueDeferred(ghost, TriggerEvent.DOC_CREATED, "doc-1", "Receipt")

        assertEquals(DrainOutcome.DONE, processor(RecordingAction(ActionType.ZIP)).process())

        assertTrue(wfRepo.listDeferred().isEmpty())
    }

    @Test
    fun `a queued share waits for the app to be in the foreground`() = runTest {
        docRepo.seedDocument("doc-1", "Receipt")
        val workflow = save(ActionType.SEND_TO_APP)
        wfRepo.enqueueDeferred(workflow, TriggerEvent.DOC_CREATED, "doc-1", "Receipt")
        val send = RecordingAction(
            ActionType.SEND_TO_APP,
            execution = Execution.FOREGROUND,
            needsNetwork = true,
        )
        foreground.foreground = false

        assertEquals(DrainOutcome.WAITING_FOR_FOREGROUND, processor(send).process())
        assertEquals(0, send.runs)
        assertEquals(1, wfRepo.listDeferred().size)

        foreground.foreground = true
        assertEquals(DrainOutcome.DONE, processor(send).process())
        assertEquals(1, send.runs)
        assertTrue(wfRepo.listDeferred().isEmpty())
    }
}
