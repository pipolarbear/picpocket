package com.picpocket.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.RedactionRect
import com.picpocket.app.ui.screens.viewer.PageViewerViewModel
import com.picpocket.app.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class PageViewerViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var repo: FakeDocumentRepository
    private lateinit var viewModel: PageViewerViewModel
    private var documentId: DocumentId = ""

    @Before
    fun setUp() = runTest(coroutineRule.dispatcher) {
        repo = FakeDocumentRepository()
        documentId = repo.createDocument("Viewer Test").getOrThrow()
        repo.addPage(documentId, "content://page1.jpg")
        repo.addPage(documentId, "content://page2.jpg")
        repo.addPage(documentId, "content://page3.jpg")
        viewModel = PageViewerViewModel(ApplicationProvider.getApplicationContext<android.app.Application>(), repo)
    }

    @Test
    fun `initial state has empty pages and isLoading`() {
        val state = viewModel.uiState.value
        assertTrue(state.pages.isEmpty())
        assertTrue(state.isLoading)
    }

    @Test
    fun `loadPages populates pages`() = runTest {
        viewModel.loadPages(documentId)
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.pages.size)
    }

    @Test
    fun `setPageIndex updates currentIndex`() {
        viewModel.setPageIndex(2)
        assertEquals(2, viewModel.uiState.value.currentIndex)

        viewModel.setPageIndex(0)
        assertEquals(0, viewModel.uiState.value.currentIndex)
    }

    @Test
    fun `redactedPreview pixelates the marked area`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(app.cacheDir, "preview_${System.nanoTime()}.png")
        val original = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (x in 0 until 200) {
            for (y in 0 until 200) {
                original.setPixel(x, y, Color.rgb(x, y, 128))
            }
        }
        file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        original.recycle()

        val page = Page(
            id = 1,
            documentId = documentId,
            pageNumber = 1,
            filename = file.name,
            imageUri = file.toURI().toString(),
            createdAt = 0,
            redactions = listOf(RedactionRect(0.1f, 0.1f, 0.6f, 0.6f)),
        )

        val preview = viewModel.redactedPreview(page, 200)
        assertNotNull(preview)
        val pixelated = preview!!

        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        var changed = 0
        for (x in 12 until 140 step 7) {
            for (y in 12 until 140 step 7) {
                if (pixelated.getPixel(x, y) != decoded.getPixel(x, y)) changed++
            }
        }
        assertTrue("expected the marked area to be pixelated", changed > 0)
        pixelated.recycle()
        decoded.recycle()
    }

    @Test
    fun `redactedPreview is null when nothing is marked`() = runTest {
        val page = Page(
            id = 1,
            documentId = documentId,
            pageNumber = 1,
            filename = "none.jpg",
            imageUri = "file:///does-not-exist.jpg",
            createdAt = 0,
        )

        assertNull(viewModel.redactedPreview(page, 200))
    }
}
