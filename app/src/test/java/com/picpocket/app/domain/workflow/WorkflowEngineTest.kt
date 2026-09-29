package com.picpocket.app.domain.workflow

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.workflow.action.DeleteAction
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowEngine
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.Artifact
import com.picpocket.app.domain.workflow.model.CompareOp
import com.picpocket.app.domain.workflow.model.Condition
import com.picpocket.app.domain.workflow.model.NodeStatus
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import com.picpocket.app.data.model.Document
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkflowEngineTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = FakeDocumentRepository()

    private val document = Document(
        id = "doc-1",
        name = "Receipt 2026",
        createdAt = 0,
        updatedAt = 0,
        pageCount = 2,
    )

    private fun engine(
        actions: List<RecordingAction>,
        pdf: FakePdfGenerator = FakePdfGenerator(),
    ): WorkflowEngine = WorkflowEngine(
        app = app,
        repository = repo,
        pdfGenerator = pdf,
        cipher = ArtifactCipher(),
        foreground = FakeForegroundExecutor(),
        registry = FakeActionRegistry(actions.associateBy { it.type }),
    )

    private fun node(id: String, type: ActionType, children: List<ActionNode> = emptyList()) =
        ActionNode(id = id, type = type, children = children)

    @Test
    fun `initially disabled by an unmatched trigger`() = runTest {
        val a = RecordingAction(ActionType.ZIP)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.RENAMED), roots = listOf(node("a", ActionType.ZIP)),
        )
        val result = engine(listOf(a)).run(workflow, TriggerEvent.DOC_CREATED, document)
        assertEquals(0, a.runs)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertTrue(result.nodes.isEmpty())
    }

    @Test
    fun `independent roots both run`() = runTest {
        val a = RecordingAction(ActionType.ZIP)
        val b = RecordingAction(ActionType.NOTIFY)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(node("a", ActionType.ZIP), node("b", ActionType.NOTIFY)),
        )
        val result = engine(listOf(a, b)).run(workflow, TriggerEvent.DOC_CREATED, document)
        assertEquals(1, a.runs)
        assertEquals(1, b.runs)
        assertEquals(2, result.nodes.count { it.status == NodeStatus.SUCCESS })
    }

    @Test
    fun `roots receive the rendered document pdf`() = runTest {
        val a = RecordingAction(ActionType.ZIP)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED), roots = listOf(node("a", ActionType.ZIP)),
        )
        engine(listOf(a)).run(workflow, TriggerEvent.DOC_CREATED, document)
        assertEquals("application/pdf", a.inputs.first()?.mime)
        assertTrue("uri should be the rendered pdf", a.inputs.first()?.uri?.endsWith(".pdf") == true)
    }

    @Test
    fun `dependent child runs after its parent`() = runTest {
        val parent = RecordingAction(ActionType.ZIP)
        val child = RecordingAction(ActionType.ENCRYPT)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(node("a", ActionType.ZIP, listOf(node("b", ActionType.ENCRYPT)))),
        )
        engine(listOf(parent, child)).run(workflow, TriggerEvent.DOC_CREATED, document)
        assertTrue("child must run", child.runs == 1)
        assertTrue("child must receive parent's artifact", child.inputs.first()?.uri == "file://x")
    }

    @Test
    fun `a non pdf parent artifact passes through unchanged`() = runTest {
        val parent = RecordingAction(
            ActionType.ZIP,
            result = ActionResult.Success(Artifact("file://archive.zip", "application/zip")),
        )
        val child = RecordingAction(ActionType.NOTIFY)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(node("a", ActionType.ZIP, listOf(node("b", ActionType.NOTIFY)))),
        )
        engine(listOf(parent, child)).run(workflow, TriggerEvent.DOC_CREATED, document)
        assertEquals("file://archive.zip", child.inputs.first()?.uri)
        assertEquals("application/zip", child.inputs.first()?.mime)
    }

    @Test
    fun `a pdf render failure fails the run without executing actions`() = runTest {
        val a = RecordingAction(ActionType.ZIP)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED), roots = listOf(node("a", ActionType.ZIP)),
        )
        val result = engine(listOf(a), pdf = FakePdfGenerator().apply { shouldFail = true })
            .run(workflow, TriggerEvent.DOC_CREATED, document)
        assertEquals(0, a.runs)
        assertEquals(RunStatus.FAILURE, result.status)
        assertTrue(result.nodes.isEmpty())
    }

    @Test
    fun `failure skips its subtree but not siblings`() = runTest {
        val root = RecordingAction(ActionType.ZIP)
        val failing = RecordingAction(ActionType.ENCRYPT, result = ActionResult.Failure("boom"))
        val sibling = RecordingAction(ActionType.NOTIFY)
        val grandchild = RecordingAction(ActionType.SAVE_TO_FOLDER)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(
                node(
                    "a", ActionType.ZIP,
                    listOf(
                        node("b", ActionType.ENCRYPT, listOf(node("d", ActionType.SAVE_TO_FOLDER))),
                        node("c", ActionType.NOTIFY),
                    ),
                ),
            ),
        )
        val result = engine(listOf(root, failing, sibling, grandchild))
            .run(workflow, TriggerEvent.DOC_CREATED, document)
        assertEquals(1, root.runs)
        assertEquals(1, failing.runs)
        assertEquals("sibling must still run", 1, sibling.runs)
        assertEquals("grandchild skipped", 0, grandchild.runs)
        assertEquals(RunStatus.PARTIAL_FAILURE, result.status)
        assertTrue(result.nodes.any { it.status == NodeStatus.FAILED && it.reason == "boom" })
    }

    @Test
    fun `all conditions must hold`() = runTest {
        val a = RecordingAction(ActionType.ZIP)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            conditions = listOf(
                Condition.PageCount(CompareOp.GE, 5),
                Condition.NameMatches("Receipt.*"),
            ),
            roots = listOf(node("a", ActionType.ZIP)),
        )
        engine(listOf(a)).run(workflow, TriggerEvent.DOC_CREATED, document)
        assertEquals("pageCount 2 < 5 so must not run", 0, a.runs)
    }

    @Test
    fun `name condition holds`() = runTest {
        val a = RecordingAction(ActionType.NOTIFY)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            conditions = listOf(Condition.NameMatches("Receipt.*")),
            roots = listOf(node("a", ActionType.NOTIFY)),
        )
        engine(listOf(a)).run(workflow, TriggerEvent.DOC_CREATED, document)
        assertEquals(1, a.runs)
    }

    @Test
    fun `manual run ignores trigger check`() = runTest {
        val a = RecordingAction(ActionType.ZIP)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.RENAMED),
            roots = listOf(node("a", ActionType.ZIP)),
        )
        engine(listOf(a)).run(workflow, TriggerEvent.MANUAL, document, ignoreTriggerCheck = true)
        assertEquals(1, a.runs)
    }

    @Test
    fun `has tag condition uses the document's tags`() = runTest {
        val tagId = repo.createTag("receipts")
        repo.seedDocument("doc-1", "Receipt 2026")
        repo.setDocumentTags("doc-1", listOf(tagId))
        val a = RecordingAction(ActionType.NOTIFY)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            conditions = listOf(Condition.HasTag(tagId)),
            roots = listOf(node("a", ActionType.NOTIFY)),
        )
        engine(listOf(a)).run(workflow, TriggerEvent.DOC_CREATED, document)
        assertEquals(1, a.runs)

        val workflowMissing = workflow.copy(conditions = listOf(Condition.HasTag(tagId + 99)))
        engine(listOf(a)).run(workflowMissing, TriggerEvent.DOC_CREATED, document)
        assertEquals("second run blocked by missing tag", 1, a.runs)
    }

    @Test
    fun `ocr text condition matches as regex`() = runTest {        repo.seedDocument("doc-1", "Receipt 2026")
        repo.addPage("doc-1", "file://p1", 0, 0L, 0)
        repo.updatePageOcrText("doc-1", 1, "Total 42 dollars")
        val a = RecordingAction(ActionType.NOTIFY)

        fun workflow(pattern: String) = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            conditions = listOf(Condition.OcrTextMatches(pattern)),
            roots = listOf(node("a", ActionType.NOTIFY)),
        )

        engine(listOf(a)).run(workflow("Total"), TriggerEvent.DOC_CREATED, document)
        assertEquals(1, a.runs)

        engine(listOf(a)).run(workflow("Total\\s+\\d+"), TriggerEvent.DOC_CREATED, document)
        assertEquals("regex alternation/quantifiers must match", 2, a.runs)

        engine(listOf(a)).run(workflow("^\\d+$"), TriggerEvent.DOC_CREATED, document)
        assertEquals("anchored non-match", 2, a.runs)

        engine(listOf(a)).run(workflow("(["), TriggerEvent.DOC_CREATED, document)
        assertEquals("invalid regex never matches", 2, a.runs)
    }

    @Test
    fun `a delete step removes the document`() = runTest {        repo.seedDocument("doc-1", "Receipt")
        val zip = RecordingAction(ActionType.ZIP)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(node("a", ActionType.ZIP, listOf(node("b", ActionType.DELETE)))),
        )
        val engine = WorkflowEngine(
            app = app,
            repository = repo,
            pdfGenerator = FakePdfGenerator(),
            cipher = ArtifactCipher(),
            foreground = FakeForegroundExecutor(),
            registry = FakeActionRegistry(
                mapOf(ActionType.ZIP to zip, ActionType.DELETE to DeleteAction()),
            ),
        )

        engine.run(workflow, TriggerEvent.DOC_CREATED, document)

        assertEquals(1, zip.runs)
        assertTrue("document must be deleted", repo.getDocument("doc-1").isFailure)
    }

    @Test
    fun `two independent branches both execute`() = runTest {
        val enc = RecordingAction(ActionType.ENCRYPT)
        val zip = RecordingAction(ActionType.ZIP)
        val save = RecordingAction(ActionType.SAVE_TO_FOLDER)
        val send = RecordingAction(ActionType.SEND_TO_APP)
        val notify = RecordingAction(ActionType.NOTIFY)
        val workflow = Workflow(
            name = "w", triggers = listOf(TriggerEvent.DOC_CREATED),
            roots = listOf(
                node(
                    "e", ActionType.ENCRYPT,
                    listOf(node("z", ActionType.ZIP, listOf(node("s", ActionType.SAVE_TO_FOLDER)))),
                ),
                node("send", ActionType.SEND_TO_APP, listOf(node("n", ActionType.NOTIFY))),
            ),
        )

        engine(listOf(enc, zip, save, send, notify)).run(workflow, TriggerEvent.DOC_CREATED, document)

        assertEquals(1, enc.runs)
        assertEquals(1, zip.runs)
        assertEquals(1, save.runs)
        assertEquals(1, send.runs)
        assertEquals(1, notify.runs)
    }
}
