package com.picpocket.app.domain.workflow.action

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.model.Document
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.storage.FakeFolderAccess
import com.picpocket.app.domain.workflow.FakeForegroundExecutor
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SaveToFolderActionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val folderAccess = FakeFolderAccess()
    private val action = SaveToFolderAction(folderAccess)
    private val treeUri = "content://com.example.documents/tree/primary%3APictures"

    private fun context() = WorkflowContext(
        document = Document(id = "doc-1", name = "Doc", createdAt = 0, updatedAt = 0, pageCount = 1),
        app = app,
        repository = FakeDocumentRepository(),
        pdfGenerator = FakePdfGenerator(),
        cipher = ArtifactCipher(),
        foreground = FakeForegroundExecutor(),
        pageSize = PageSize.A4,
    )

    private suspend fun run(uri: String): ActionResult =
        action.execute(context(), null, ActionParams.SaveToFolder(uri, "Pictures"))

    @Test
    fun `a blank folder uri is not configured`() = runTest {
        val result = run("")
        assertTrue(result is ActionResult.Failure)
        assertEquals("save-to-folder: folder not configured", (result as ActionResult.Failure).reason)
    }

    @Test
    fun `a folder whose grant was not persisted fails with a clear reason`() = runTest {
        val result = run(treeUri)
        assertTrue(result is ActionResult.Failure)
        assertEquals(
            "save-to-folder: folder access was revoked; pick it again",
            (result as ActionResult.Failure).reason,
        )
    }

    @Test
    fun `a persisted folder that no longer resolves is reported missing`() = runTest {
        folderAccess.persist(treeUri)
        val result = run(treeUri)
        assertTrue(result is ActionResult.Failure)
        assertEquals("save-to-folder: target folder not found", (result as ActionResult.Failure).reason)
    }

    @Test
    fun `the tree uri is checked as persisted before writing`() = runTest {
        assertTrue(!folderAccess.isPersisted(Uri.parse(treeUri)))
    }
}
