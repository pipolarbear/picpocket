package com.picpocket.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.ocr.BlockingOcrEngine
import com.picpocket.app.domain.ocr.FakeOcrEngine
import com.picpocket.app.domain.ocr.OcrManager
import com.picpocket.app.domain.scanner.FakeScannerManager
import com.picpocket.app.domain.scanner.ScannerResult
import com.picpocket.app.domain.storage.FakeFolderWriter
import com.picpocket.app.ui.screens.detail.DocumentDetailScreen
import com.picpocket.app.ui.screens.detail.DocumentDetailViewModel
import com.picpocket.app.util.TestBitmapFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentDetailScreenRescanTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = DocumentStore(app)
    private val repo = FakeDocumentRepository()

    private fun waitForHierarchy(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val nodes = try {
                composeRule.waitForIdle()
                composeRule.onAllNodesWithText("Pages").fetchSemanticsNodes()
            } catch (_: IllegalStateException) {
                emptyList()
            }
            if (nodes.isNotEmpty()) return
            android.os.SystemClock.sleep(100)
        }
    }

    private fun waitForRescanNodes(timeoutMillis: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val nodes = try {
                composeRule.waitForIdle()
                composeRule.onAllNodesWithContentDescription("Rescan page").fetchSemanticsNodes()
            } catch (_: IllegalStateException) {
                emptyList()
            }
            if (nodes.isNotEmpty()) return
            android.os.SystemClock.sleep(100)
        }
    }

    private fun seedDocumentInStoreWithJpegPage(): String = runBlocking {
        val storedId = store.createDocument("Rescan Doc", qualityTier = 0).getOrThrow().id
        val bitmap = TestBitmapFactory.allBlack()
        val dir = store.documentDir(storedId)
        dir.mkdirs()
        val file = File(dir, "page_001.jpg")
        file.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }
        store.addPage(storedId, 1, file.name, file.length()).getOrThrow()
        storedId
    }

    @Test
    fun rescanButtonHiddenWhileOcrRunningThenAppears() {
        val documentId = seedDocumentInStoreWithJpegPage()
        repo.seedDocument(documentId, "Rescan Doc", ocrComplete = false)
        runBlocking { repo.addPage(documentId, "content://page1.jpg") }

        val ocrEngine = BlockingOcrEngine()
        val viewModel = DocumentDetailViewModel(
            app,
            repo,
            store,
            FakePdfGenerator(),
            OcrManager(ocrEngine, store),
            FakeScannerManager(),
            FakeFolderWriter(),
        )

        composeRule.setContent {
            DocumentDetailScreen(
                documentId = documentId,
                onNavigateBack = {},
                onAddPage = {},
                viewModel = viewModel,
            )
        }

        viewModel.loadDocument(documentId)
        composeRule.waitForIdle()

        waitForHierarchy()
        assertTrue(
            "Rescan button must be hidden while OCR is running",
            composeRule.onAllNodesWithContentDescription("Rescan page")
                .fetchSemanticsNodes().isEmpty(),
        )

        ocrEngine.gate.complete(Unit)
        waitForRescanNodes()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Rescan page").assertIsDisplayed()
    }

    @Test
    fun cancelledRescanLeavesPageUnchanged() {
        val documentId = seedDocumentInStoreWithJpegPage()
        repo.seedDocument(documentId, "Rescan Doc", ocrComplete = true)
        runBlocking { repo.addPage(documentId, "content://page1.jpg") }

        val scannerManager = FakeScannerManager()
        scannerManager.resultToReturn = ScannerResult.Cancelled

        val viewModel = DocumentDetailViewModel(
            app,
            repo,
            store,
            FakePdfGenerator(),
            OcrManager(FakeOcrEngine(), store),
            scannerManager,
            FakeFolderWriter(),
        )

        composeRule.setContent {
            DocumentDetailScreen(
                documentId = documentId,
                onNavigateBack = {},
                onAddPage = {},
                viewModel = viewModel,
            )
        }

        viewModel.loadDocument(documentId)
        composeRule.waitForIdle()

        waitForRescanNodes()
        composeRule.onNodeWithContentDescription("Rescan page").assertIsDisplayed()

        viewModel.handleRescanScannerResult(pageNumber = 1, data = null)
        composeRule.waitForIdle()

        assertEquals("no rescan should be performed on cancel", 0, repo.rescanCallCount)
        val page1 = runBlocking { repo.getPages(documentId) }.getOrThrow().first { it.pageNumber == 1 }
        assertEquals("content://page1.jpg", page1.imageUri)
        composeRule.onNodeWithContentDescription("Rescan page").assertIsDisplayed()
    }

    @Test
    fun singlePageScanReplacesPageImage() {
        val documentId = seedDocumentInStoreWithJpegPage()
        repo.seedDocument(documentId, "Rescan Doc", ocrComplete = true)
        runBlocking { repo.addPage(documentId, "content://page1.jpg") }

        val scannerManager = FakeScannerManager()
        scannerManager.resultToReturn =
            ScannerResult.PageCaptured(android.net.Uri.parse("content://scanner/new.jpg"))

        val viewModel = DocumentDetailViewModel(
            app,
            repo,
            store,
            FakePdfGenerator(),
            OcrManager(FakeOcrEngine(), store),
            scannerManager,
            FakeFolderWriter(),
        )

        composeRule.setContent {
            DocumentDetailScreen(
                documentId = documentId,
                onNavigateBack = {},
                onAddPage = {},
                viewModel = viewModel,
            )
        }

        viewModel.loadDocument(documentId)
        composeRule.waitForIdle()

        viewModel.handleRescanScannerResult(pageNumber = 1, data = null)
        composeRule.waitForIdle()

        assertEquals(1, repo.rescanCallCount)
        val page1 = runBlocking { repo.getPages(documentId) }.getOrThrow().first { it.pageNumber == 1 }
        assertEquals("content://scanner/new.jpg", page1.imageUri)
    }
}