package com.picpocket.app.domain.workflow.action

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.model.Document
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.workflow.FakeForegroundExecutor
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.Artifact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeleteActionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = FakeDocumentRepository()
    private val action = DeleteAction()

    private fun context() = WorkflowContext(
        document = Document(id = "doc-1", name = "Doc", createdAt = 0, updatedAt = 0, pageCount = 1),
        app = app,
        repository = repo,
        pdfGenerator = FakePdfGenerator(),
        cipher = ArtifactCipher(),
        foreground = FakeForegroundExecutor(),
        pageSize = PageSize.A4,
    )

    @Test
    fun `deletes the triggering document`() = runTest {
        repo.seedDocument("doc-1", "Doc")
        val result = action.execute(context(), Artifact("file://x", "application/pdf"), ActionParams.None)
        assertTrue(result is ActionResult.Success)
        assertTrue("document must be gone", repo.getDocument("doc-1").isFailure)
    }

    @Test
    fun `a failing delete returns a failure`() = runTest {
        repo.seedDocument("doc-1", "Doc")
        repo.failDeleteDocument = true
        val result = action.execute(context(), Artifact("file://x", "application/pdf"), ActionParams.None)
        assertTrue(result is ActionResult.Failure)
    }
}
