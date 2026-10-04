package com.picpocket.app.ui

import android.app.Application
import android.content.Context
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.store.DocumentStore
import io.mockk.mockk
import com.picpocket.app.domain.ocr.FakeOcrEngine
import com.picpocket.app.domain.ocr.OcrManager
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.scanner.ScannerManager
import com.picpocket.app.domain.scanner.ScannerResult
import com.picpocket.app.ui.screens.detail.DocumentDetailViewModel
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class DocumentDetailViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var viewModel: DocumentDetailViewModel
    private var documentId: String = ""
    private lateinit var scannerManager: ScannerManager

    @Before
    fun setUp() = runTest(coroutineRule.dispatcher) {
        repo = FakeDocumentRepository()
        documentId = repo.createDocument("My Document").getOrThrow()
        repo.addPage(documentId, "content://page1.jpg")
        repo.addPage(documentId, "content://page2.jpg")
        val app = RuntimeEnvironment.getApplication() as Application
        val store = DocumentStore(app)
        scannerManager = mockk(relaxed = true)
        viewModel = DocumentDetailViewModel(
            app,
            repo,
            store,
            FakePdfGenerator(),
            OcrManager(FakeOcrEngine(), store),
            scannerManager,
        )
    }

    @Test
    fun `show and hide rename dialog with loaded document`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showRenameDialog()
        assertTrue(viewModel.uiState.value.showRenameDialog)
        assertEquals("My Document", viewModel.uiState.value.renameText)

        viewModel.hideRenameDialog()
        assertFalse(viewModel.uiState.value.showRenameDialog)
    }

    @Test
    fun `rename document updates name`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showRenameDialog()
        viewModel.updateRenameText("Renamed Document")
        viewModel.renameDocument()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val doc = repo.getDocument(documentId).getOrNull()
        assertEquals("Renamed Document", doc?.name)
        assertFalse(viewModel.uiState.value.showRenameDialog)
    }

    @Test
    fun `update rename text`() {
        viewModel.updateRenameText("New Name")
        assertEquals("New Name", viewModel.uiState.value.renameText)
    }

    @Test
    fun `toggle edit mode`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isEditMode)
        viewModel.toggleEditMode()
        assertTrue(viewModel.uiState.value.isEditMode)
        viewModel.toggleEditMode()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isEditMode)
    }

    @Test
    fun `toggle mark for deletion in edit mode`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val pagesBefore = viewModel.uiState.value.pages
        assertEquals(2, pagesBefore.size)

        viewModel.toggleEditMode()
        assertTrue(viewModel.uiState.value.isEditMode)

        val page = pagesBefore[0]
        viewModel.toggleMarkForDeletion(page.filename)
        assertTrue(viewModel.uiState.value.markedForDeletion.contains(page.filename))

        viewModel.toggleMarkForDeletion(page.filename)
        assertFalse(viewModel.uiState.value.markedForDeletion.contains(page.filename))
    }

    @Test
    fun `showDeleteConfirmation toggles dialog`() {
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
        viewModel.showDeleteConfirmation()
        assertTrue(viewModel.uiState.value.showDeleteConfirmation)
        viewModel.dismissDeleteConfirmation()
        assertFalse(viewModel.uiState.value.showDeleteConfirmation)
    }

    @Test
    fun `deleteDocument removes document from state`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.document)

        viewModel.deleteDocument()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.document)
    }

    @Test
    fun `rename to existing name shows rename overwrite dialog`() = runTest {
        repo.createDocument("Target Name")
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showRenameDialog()
        viewModel.updateRenameText("Target Name")
        viewModel.renameDocument()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertTrue("renameOverwriteDialog should show when rename targets existing name",
            viewModel.uiState.value.showRenameOverwriteDialog)
    }

    @Test
    fun `confirm rename overwrite renames and hides dialog`() = runTest {
        repo.createDocument("Target Name")
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showRenameDialog()
        viewModel.updateRenameText("Target Name")
        viewModel.renameDocument()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue("renameOverwriteDialog should show", viewModel.uiState.value.showRenameOverwriteDialog)

        viewModel.confirmRenameOverwrite()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse("Dialog should be dismissed after confirm", viewModel.uiState.value.showRenameOverwriteDialog)
        val doc = repo.getDocument(documentId).getOrNull()
        assertEquals("Document should be renamed to target", "Target Name", doc?.name)
    }

    @Test
    fun `cancel rename overwrite dismisses dialog`() = runTest {
        repo.createDocument("Target Name")
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showRenameDialog()
        viewModel.updateRenameText("Target Name")
        viewModel.renameDocument()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.cancelRenameOverwrite()
        assertFalse("Dialog should be dismissed after cancel", viewModel.uiState.value.showRenameOverwriteDialog)
    }

    @Test
    fun `renaming to unique name does not trigger overwrite dialog`() = runTest {
        repo.createDocument("Unrelated Name")
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showRenameDialog()
        viewModel.updateRenameText("Totally New Name")
        viewModel.renameDocument()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse("Overwrite dialog should not show for unique rename",
            viewModel.uiState.value.showRenameOverwriteDialog)
        val doc = repo.getDocument(documentId).getOrNull()
        assertEquals("Totally New Name", doc?.name)
    }

    @Test
    fun `empty delete shows warning when all pages marked`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleEditMode()
        assertTrue(viewModel.uiState.value.isEditMode)

        val pages = viewModel.uiState.value.reorderablePages
        assertTrue("Should have pages to mark", pages.size >= 2)
        pages.forEach { page ->
            viewModel.toggleMarkForDeletion(page.filename)
        }

        viewModel.toggleEditMode()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertTrue("Empty delete dialog should show when all pages marked",
            viewModel.uiState.value.showEmptyDeleteDialog)
    }

    @Test
    fun `empty delete warning dismissed by confirm`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleEditMode()
        val pages = viewModel.uiState.value.reorderablePages
        pages.forEach { viewModel.toggleMarkForDeletion(it.filename) }
        viewModel.toggleEditMode()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue("Empty delete dialog should show", viewModel.uiState.value.showEmptyDeleteDialog)

        viewModel.confirmEmptyDelete()
        assertFalse("Dialog should dismiss", viewModel.uiState.value.showEmptyDeleteDialog)
    }

    @Test
    fun `cancel empty delete dismisses dialog`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleEditMode()
        val pages = viewModel.uiState.value.reorderablePages
        pages.forEach { viewModel.toggleMarkForDeletion(it.filename) }
        viewModel.toggleEditMode()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue("Empty delete dialog should show", viewModel.uiState.value.showEmptyDeleteDialog)

        viewModel.cancelEmptyDelete()
        assertFalse("Dialog should dismiss", viewModel.uiState.value.showEmptyDeleteDialog)
    }

    @Test
    fun `rescanPage handles failure gracefully`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        repo.failRescanPage = true

        viewModel.rescanPage(pageNumber = 1, imageUri = "content://fake.jpg")
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showRescanProgress)
        assertNull(viewModel.uiState.value.rescanPageNumber)
    }

    @Test
    fun `handleRescanScannerResult with single page rescans the page`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val uri = android.net.Uri.parse("content://scanner/result.jpg")
        io.mockk.coEvery { scannerManager.handleResult(any()) } returns ScannerResult.PageCaptured(uri)

        viewModel.handleRescanScannerResult(pageNumber = 1, data = null)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showRescanProgress)
        assertNull(viewModel.uiState.value.rescanPageNumber)
        assertEquals(1, repo.rescanCallCount)
        val page1 = repo.getPages(documentId).getOrThrow().first { it.pageNumber == 1 }
        assertEquals("content://scanner/result.jpg", page1.imageUri)
    }

    @Test
    fun `handleRescanScannerResult with multiple pages shows error and rescans nothing`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val uris = listOf(
            android.net.Uri.parse("content://scanner/1.jpg"),
            android.net.Uri.parse("content://scanner/2.jpg"),
        )
        io.mockk.coEvery { scannerManager.handleResult(any()) } returns ScannerResult.MultiplePagesCaptured(uris)

        viewModel.handleRescanScannerResult(pageNumber = 1, data = null)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.rescanPageNumber)
        assertEquals(0, repo.rescanCallCount)
        val page1 = repo.getPages(documentId).getOrThrow().first { it.pageNumber == 1 }
        assertEquals("content://page1.jpg", page1.imageUri)
    }

    @Test
    fun `handleRescanScannerResult with single-item multiple pages still rescans`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val uris = listOf(android.net.Uri.parse("content://scanner/result.jpg"))
        io.mockk.coEvery { scannerManager.handleResult(any()) } returns ScannerResult.MultiplePagesCaptured(uris)

        viewModel.handleRescanScannerResult(pageNumber = 1, data = null)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showRescanProgress)
        assertNull(viewModel.uiState.value.rescanPageNumber)
        assertEquals(1, repo.rescanCallCount)
        val page1 = repo.getPages(documentId).getOrThrow().first { it.pageNumber == 1 }
        assertEquals("content://scanner/result.jpg", page1.imageUri)
    }

    @Test
    fun `handleRescanScannerResult with cancel clears rescan state`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        io.mockk.coEvery { scannerManager.handleResult(any()) } returns ScannerResult.Cancelled

        viewModel.handleRescanScannerResult(pageNumber = 1, data = null)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.rescanPageNumber)
        assertEquals(0, repo.rescanCallCount)
    }

    @Test
    fun `beginRescan requests single page scanner and stores intent sender`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val sender = io.mockk.mockk<android.content.IntentSender>()
        io.mockk.coEvery { scannerManager.getStartScanIntentSender(any(), any()) } returns sender

        viewModel.beginRescan(
            org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).get(),
            pageNumber = 1,
        )
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        io.mockk.coVerify { scannerManager.getStartScanIntentSender(any(), pageLimit = 1) }
        assertEquals(sender, viewModel.uiState.value.pendingRescanIntentSender)
        assertEquals(1, viewModel.uiState.value.rescanPageNumber)
    }

    @Test
    fun `collate defaults to all pages selected`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showCollateDialog()

        assertEquals(setOf(1, 2), viewModel.uiState.value.collateSelection)
        assertEquals(listOf(1, 2), viewModel.uiState.value.collateOrder)
    }

    @Test
    fun `deselecting clears select-all and reordering updates the order`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        viewModel.showCollateDialog()

        viewModel.moveCollateSource(0, 1)
        assertEquals(listOf(2, 1), viewModel.uiState.value.collateOrder)

        viewModel.toggleCollateSelection(2)
        assertEquals(setOf(1), viewModel.uiState.value.collateSelection)
        assertEquals(listOf(1), viewModel.uiState.value.collateOrder)
    }

    @Test
    fun `collate defaults keep sources and match sizes`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.showCollateDialog()

        assertFalse(viewModel.uiState.value.collateRemoveSources)
        assertTrue(viewModel.uiState.value.collateMatchSizes)
        assertEquals(com.picpocket.app.domain.collate.CollateLayout.AUTO, viewModel.uiState.value.collateLayout)
    }

    @Test
    fun `reset collate bumps the reset token`() = runTest {
        viewModel.loadDocument(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        viewModel.showCollateDialog()

        val before = viewModel.uiState.value.collateResetToken
        viewModel.resetCollate()

        assertTrue(viewModel.uiState.value.collateResetToken > before)
    }
}
