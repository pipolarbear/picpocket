package com.picpocket.app.domain.workflow.action

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.model.Document
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.storage.FakeFolderAccess
import com.picpocket.app.domain.storage.FakeFolderWriter
import com.picpocket.app.domain.storage.copyArtifact
import com.picpocket.app.domain.storage.writeProblem
import com.picpocket.app.domain.workflow.FakeForegroundExecutor
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SaveToFolderActionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val folderAccess = FakeFolderAccess()
    private val folderWriter = FakeFolderWriter()
    private val action = SaveToFolderAction(folderAccess, folderWriter)
    private val treeUri = "content://com.example.documents/tree/primary%3APictures"

    private fun context() = WorkflowContext(
        document = Document(id = "doc-1", name = "Doc", createdAt = 0, updatedAt = 0, pageCount = 1),
        app = app,
        repository = FakeDocumentRepository(),
        pdfGenerator = FakePdfGenerator(),
        cipher = ArtifactCipher(),
        foreground = FakeForegroundExecutor(),
        folderAccess = folderAccess,
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
        folderWriter.writeResult = Result.failure(IOException("could not open the picked folder"))
        val result = run(treeUri)
        assertTrue(result is ActionResult.Failure)
        assertEquals(
            "save-to-folder: could not open the picked folder",
            (result as ActionResult.Failure).reason,
        )
    }

    @Test
    fun `a successful write passes the artifact through`() = runTest {
        folderAccess.persist(treeUri)
        val result = run(treeUri)
        assertTrue(result is ActionResult.Success)
        assertEquals(1, folderWriter.writes.size)
        assertEquals("Doc.pdf", folderWriter.writes.single().name)
        assertTrue((result as ActionResult.Success).note!!.contains("Doc.pdf"))
    }

    @Test
    fun `the tree uri is checked as persisted before writing`() = runTest {
        assertTrue(!folderAccess.isPersisted(Uri.parse(treeUri)))
    }

    @Test
    fun `a cloud folder needs a network connection`() {
        val driveUri = "content://com.google.android.apps.docs.storage/tree/drive"
        folderAccess.remoteAuthorities.add("com.google.android.apps.docs.storage")
        assertTrue(
            action.requiresNetwork(context(), ActionParams.SaveToFolder(driveUri, "Drive")),
        )
    }

    @Test
    fun `a device folder does not need a network connection`() {
        val localUri = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        assertTrue(!action.requiresNetwork(context(), ActionParams.SaveToFolder(localUri, "Pictures")))
    }

    @Test
    fun `a missing destination stream is an error, not a silent success`() {
        val error = runCatching {
            copyArtifact(ByteArrayInputStream(byteArrayOf(1, 2, 3)), null)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("destination"))
    }

    @Test
    fun `a missing source stream is an error`() {
        val error = runCatching { copyArtifact(null, null) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("source file"))
    }

    @Test
    fun `a short write is reported`() {
        assertEquals("wrote 4 of 10 bytes", writeProblem(expected = 10, copied = 4, stored = -1))
    }

    @Test
    fun `a complete write is accepted even when the provider reports no size`() {
        assertNull(writeProblem(expected = 10, copied = 10, stored = 10))
        assertNull(writeProblem(expected = 10, copied = 10, stored = 0))
        assertNull(writeProblem(expected = null, copied = 10, stored = -1))
    }

    @Test
    fun `a mismatched stored size is reported`() {
        assertTrue(writeProblem(expected = 10, copied = 10, stored = 4)!!.contains("folder reports"))
    }
}
