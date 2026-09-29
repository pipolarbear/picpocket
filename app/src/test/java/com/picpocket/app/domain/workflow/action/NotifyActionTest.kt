package com.picpocket.app.domain.workflow.action

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class NotifyActionTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val action = NotifyAction()

    private fun context() = WorkflowContext(
        document = Document(id = "doc-1", name = "Doc", createdAt = 0, updatedAt = 0, pageCount = 1),
        app = app,
        repository = FakeDocumentRepository(),
        pdfGenerator = FakePdfGenerator(),
        cipher = ArtifactCipher(),
        foreground = FakeForegroundExecutor(),
        pageSize = PageSize.A4,
    )

    @Test
    fun `posts a notification and passes the artifact through`() = runTest {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val input = Artifact("file://artifact", "application/pdf")

        val result = action.execute(context(), input, ActionParams.Notify("done"))

        assertTrue(result is ActionResult.Success)
        assertEquals("file://artifact", (result as ActionResult.Success).artifact.uri)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, Shadows.shadowOf(manager).allNotifications.size)
    }
}
