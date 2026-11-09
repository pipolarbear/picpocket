package com.picpocket.app.domain.workflow.action

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.model.Document
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.storage.FakeFolderAccess
import com.picpocket.app.domain.storage.FakeFolderWriter
import com.picpocket.app.domain.workflow.FakeForegroundExecutor
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RedactActionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val pdf = FakePdfGenerator()
    private val folderAccess = FakeFolderAccess()
    private val action = RedactAction()

    private fun context(hasRedactions: Boolean) = WorkflowContext(
        document = Document(
            id = "doc-1",
            name = "Doc",
            createdAt = 0,
            updatedAt = 0,
            pageCount = 1,
            hasRedactions = hasRedactions,
        ),
        app = app,
        repository = FakeDocumentRepository(),
        pdfGenerator = pdf,
        cipher = ArtifactCipher(),
        foreground = FakeForegroundExecutor(),
        folderAccess = folderAccess,
        pageSize = PageSize.A4,
    )

    @Test
    fun `fails when the document has nothing marked`() = runTest {
        val result = action.execute(context(hasRedactions = false), null, ActionParams.None)

        assertTrue(result is ActionResult.Failure)
        assertEquals(
            "redact: this document has no redacted areas",
            (result as ActionResult.Failure).reason,
        )
        assertTrue(pdf.redactedFlags.isEmpty())
    }

    @Test
    fun `returns a redacted artifact when areas exist`() = runTest {
        val result = action.execute(context(hasRedactions = true), null, ActionParams.None)

        assertTrue(result is ActionResult.Success)
        val artifact = (result as ActionResult.Success).artifact
        assertTrue(artifact.redacted)
        assertEquals(listOf(true), pdf.redactedFlags)
    }

    @Test
    fun `the redacted pdf is rendered only once per run`() = runTest {
        val ctx = context(hasRedactions = true)
        action.execute(ctx, null, ActionParams.None)
        action.execute(ctx, null, ActionParams.None)

        assertEquals(1, pdf.redactedFlags.size)
    }

    @Test
    fun `a save step asked to redact without marked areas fails`() = runTest {
        val save = SaveToFolderAction(folderAccess, FakeFolderWriter())
        folderAccess.persist(TREE_URI)
        val params = ActionParams.SaveToFolder(
            folderUri = TREE_URI,
            displayName = "Pictures",
            redact = true,
        )

        val failure = runCatching { save.execute(context(hasRedactions = false), null, params) }
            .exceptionOrNull()

        assertNotNull(failure)
        assertEquals("This document has no redacted areas", failure?.message)
    }

    @Test
    fun `a save step asked to redact uses the redacted generator`() = runTest {
        val save = SaveToFolderAction(folderAccess, FakeFolderWriter())
        folderAccess.persist(TREE_URI)
        val params = ActionParams.SaveToFolder(
            folderUri = TREE_URI,
            displayName = "Pictures",
            redact = true,
        )

        save.execute(context(hasRedactions = true), null, params)

        assertEquals(listOf(true), pdf.redactedFlags)
    }

    private companion object {
        const val TREE_URI = "content://com.example.documents/tree/primary%3APictures"
    }
}
