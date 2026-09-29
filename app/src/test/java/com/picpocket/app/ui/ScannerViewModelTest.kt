package com.picpocket.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.domain.filter.BinarizeFilter
import com.picpocket.app.domain.filter.BrightnessFilter
import com.picpocket.app.domain.filter.ContrastFilter
import com.picpocket.app.domain.filter.FilterPipeline
import com.picpocket.app.domain.filter.FilterType
import com.picpocket.app.domain.filter.GrayscaleFilter
import com.picpocket.app.domain.filter.SharpenFilter
import com.picpocket.app.domain.ocr.FakeOcrEngine
import com.picpocket.app.domain.ocr.OcrManager
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.scanner.ScannerResult
import com.picpocket.app.ui.screens.scanner.ScannerViewModel
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class ScannerViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var ocrManager: OcrManager
    private lateinit var viewModel: ScannerViewModel

    @Before
    fun setUp() {
        repo = FakeDocumentRepository()
        val app = RuntimeEnvironment.getApplication() as Application
        ocrManager = OcrManager(FakeOcrEngine(), DocumentStore(app))
        val pipeline = FilterPipeline(
            GrayscaleFilter(), ContrastFilter(), BrightnessFilter(),
            SharpenFilter(), BinarizeFilter(),
        )
        viewModel = ScannerViewModel(
            app,
            repo,
            com.picpocket.app.domain.scanner.ScannerManager(),
            pipeline,
            ocrManager,
        )
    }

    @Test
    fun `initial state has no pages`() {
        val state = viewModel.uiState.value
        assertTrue(state.capturedPages.isEmpty())
        assertFalse(state.isSaving)
    }

    @Test
    fun `default document name starts with Scan_`() {
        val name = viewModel.uiState.value.documentName
        assertTrue("Default name should start with Scan_", name.startsWith("Scan_"))
    }

    @Test
    fun `default document name is not blank`() {
        assertFalse(viewModel.uiState.value.documentName.isBlank())
    }

    @Test
    fun `adding a page increments page count`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://test/page.jpg"))
        )
        assertEquals(1, viewModel.uiState.value.capturedPages.size)
    }

    @Test
    fun `adding multiple pages increments correctly`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page1.jpg"))
        )
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page2.jpg"))
        )
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page3.jpg"))
        )
        assertEquals(3, viewModel.uiState.value.capturedPages.size)
    }

    @Test
    fun `cancelled scanner adds no pages`() {
        viewModel.onScannerResult(ScannerResult.Cancelled)
        assertTrue(viewModel.uiState.value.capturedPages.isEmpty())
    }

    @Test
    fun `apply filter updates filterTypes on the page`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page.jpg"))
        )
        viewModel.applyFilter(0, FilterType.GRAYSCALE)
        assertTrue(
            viewModel.uiState.value.capturedPages[0].filterTypes.contains(FilterType.GRAYSCALE)
        )
    }

    @Test
    fun `remove page decreases count`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page1.jpg"))
        )
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page2.jpg"))
        )
        viewModel.removePage(0)
        assertEquals(1, viewModel.uiState.value.capturedPages.size)
    }

    @Test
    fun `show and hide name dialog`() {
        assertFalse(viewModel.uiState.value.showNameDialog)
        viewModel.showNameDialog()
        assertTrue(viewModel.uiState.value.showNameDialog)
        viewModel.hideNameDialog()
        assertFalse(viewModel.uiState.value.showNameDialog)
    }

    @Test
    fun `update document name`() {
        viewModel.updateDocumentName("My Document")
        assertEquals("My Document", viewModel.uiState.value.documentName)
    }

    @Test
    fun `confirmNameAndSave with no pages does nothing`() = runTest {
        viewModel.confirmNameAndSave()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.savedDocumentId)
        assertFalse(viewModel.uiState.value.showTagsDialog)
    }

    private fun createTempImageUri(): Uri {
        val ctx = RuntimeEnvironment.getApplication()
        val file = File(ctx.cacheDir, "test_page_${System.nanoTime()}.png")
        val bitmap = android.graphics.Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    @Test
    fun `confirmNameAndSave saves document and shows tags dialog`() = runTest {
        viewModel.updateDocumentName("MyDoc")
        val uri = createTempImageUri()
        viewModel.onScannerResult(ScannerResult.PageCaptured(uri))
        assertFalse("capturedPages should not be empty", viewModel.uiState.value.capturedPages.isEmpty())
        assertFalse("documentName should not be blank", viewModel.uiState.value.documentName.isBlank())
        viewModel.confirmNameAndSave()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertNull("saveError should be null: " + viewModel.uiState.value.saveError, viewModel.uiState.value.saveError)
        assertFalse("isSaving should be false", viewModel.uiState.value.isSaving)
        assertTrue("showTagsDialog should be true after save", viewModel.uiState.value.showTagsDialog)
        val docs = repo.observeDocuments().first()
        assertEquals(1, docs.size)
        assertEquals("MyDoc", docs[0].name)
    }

    @Test
    fun `completeSave sets savedDocumentId and clears tags dialog`() = runTest {
        viewModel.updateDocumentName("MyDoc")
        viewModel.onScannerResult(ScannerResult.PageCaptured(createTempImageUri()))
        viewModel.confirmNameAndSave()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue("showTagsDialog should be true", viewModel.uiState.value.showTagsDialog)

        viewModel.completeSave()
        assertNotNull("savedDocumentId should be set", viewModel.uiState.value.savedDocumentId)
        assertFalse("showTagsDialog should be false", viewModel.uiState.value.showTagsDialog)
    }

    @Test
    fun `toggleTag applies tag immediately after save`() = runTest {
        val tagId = repo.createTag("Work")
        viewModel.updateDocumentName("MyDoc")
        viewModel.onScannerResult(ScannerResult.PageCaptured(createTempImageUri()))
        viewModel.confirmNameAndSave()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue("showTagsDialog should be true", viewModel.uiState.value.showTagsDialog)

        viewModel.toggleTag(tagId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val docs = repo.observeDocuments().first()
        val docTags = repo.observeDocumentTags(docs[0].id).first()
        assertEquals(1, docTags.size)
    }

    @Test
    fun `append mode sets isAppendMode flag`() = runTest {
        val docId = repo.createDocument("Append Test").getOrThrow()
        viewModel.setExistingDocumentId(docId)
        assertTrue(viewModel.uiState.value.isAppendMode)
    }

    @Test
    fun `append mode adds captured page to list`() = runTest {
        val docId = repo.createDocument("Append Test").getOrThrow()
        viewModel.setExistingDocumentId(docId)

        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://test/page.jpg"))
        )

        assertEquals(1, viewModel.uiState.value.capturedPages.size)
        assertTrue(viewModel.uiState.value.isAppendMode)
    }

    @Test
    fun `show and hide filter sheet`() {
        viewModel.onScannerResult(
            ScannerResult.PageCaptured(Uri.parse("content://page.jpg"))
        )
        viewModel.showFilterSheet(0)
        assertTrue(viewModel.uiState.value.showFilterSheet)
        assertEquals(0, viewModel.uiState.value.currentPageIndex)

        viewModel.hideFilterSheet()
        assertFalse(viewModel.uiState.value.showFilterSheet)
    }

    @Test
    fun `showDiscardDialog sets state`() {
        assertFalse(viewModel.uiState.value.showDiscardDialog)
        viewModel.showDiscardDialog()
        assertTrue(viewModel.uiState.value.showDiscardDialog)
    }

    @Test
    fun `hideDiscardDialog clears state`() {
        viewModel.showDiscardDialog()
        viewModel.hideDiscardDialog()
        assertFalse(viewModel.uiState.value.showDiscardDialog)
    }

    @Test
    fun `duplicate name shows overwrite dialog`() = runTest {
        repo.createDocument("Existing Doc")
        viewModel.updateDocumentName("Existing Doc")
        val uri = createTempImageUri()
        viewModel.onScannerResult(ScannerResult.PageCaptured(uri))
        viewModel.confirmNameAndSave()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertTrue("Overwrite dialog should be shown for duplicate name", viewModel.uiState.value.showOverwriteDialog)
        assertFalse("Should not be saving when waiting for overwrite confirmation", viewModel.uiState.value.isSaving)
    }

    @Test
    fun `overwrite confirmed replaces existing document`() = runTest {
        repo.createDocument("Existing Doc")
        viewModel.updateDocumentName("Existing Doc")
        val uri = createTempImageUri()
        viewModel.onScannerResult(ScannerResult.PageCaptured(uri))
        viewModel.confirmNameAndSave()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue("Overwrite dialog should be shown", viewModel.uiState.value.showOverwriteDialog)

        viewModel.confirmOverwrite()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertTrue("Tags dialog should show after confirm", viewModel.uiState.value.showTagsDialog)

        viewModel.completeSave()
        assertNotNull("savedDocumentId should be set after completeSave", viewModel.uiState.value.savedDocumentId)
        val docs = repo.observeDocuments().first()
        assertEquals("Only one document should exist (replaced)", 1, docs.size)
    }

    @Test
    fun `cancel overwrite aborts save`() = runTest {
        repo.createDocument("Existing Doc")
        viewModel.updateDocumentName("Existing Doc")
        val uri = createTempImageUri()
        viewModel.onScannerResult(ScannerResult.PageCaptured(uri))
        viewModel.confirmNameAndSave()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()
        assertTrue("Overwrite dialog should be shown", viewModel.uiState.value.showOverwriteDialog)

        viewModel.cancelOverwrite()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse("Overwrite dialog should be dismissed", viewModel.uiState.value.showOverwriteDialog)
        assertNull("Document should not be saved after cancel", viewModel.uiState.value.savedDocumentId)
    }

    @Test
    fun `unique name does not trigger overwrite dialog`() = runTest {
        repo.createDocument("Other Doc")
        viewModel.updateDocumentName("My Unique Doc")
        val uri = createTempImageUri()
        viewModel.onScannerResult(ScannerResult.PageCaptured(uri))
        viewModel.confirmNameAndSave()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        assertFalse("Overwrite dialog should not show for unique name", viewModel.uiState.value.showOverwriteDialog)
        assertTrue("Tags dialog should show after save", viewModel.uiState.value.showTagsDialog)

        viewModel.completeSave()
        assertNotNull("Document should be saved normally", viewModel.uiState.value.savedDocumentId)
    }

    @Test
    fun `per-doc page size is passed to createDocument`() = runTest {
        viewModel.setExportPageSize(com.picpocket.app.domain.export.PageSize.A3)
        viewModel.updateDocumentName("PageSizeTest")
        val uri = createTempImageUri()
        viewModel.onScannerResult(ScannerResult.PageCaptured(uri))
        viewModel.confirmNameAndSave()
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        viewModel.completeSave()
        val savedId = viewModel.uiState.value.savedDocumentId ?: error("No saved document")
        val doc = repo.getDocument(savedId).getOrNull()
        assertNotNull(doc)
        assertEquals("PageSize should be A3", "A3", doc!!.pageSize)
    }
}
