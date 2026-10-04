package com.picpocket.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.data.FakeDocumentRepository
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.domain.collate.CollateFixtures
import com.picpocket.app.domain.export.FakePdfGenerator
import com.picpocket.app.domain.ocr.FakeOcrEngine
import com.picpocket.app.domain.ocr.OcrManager
import com.picpocket.app.domain.scanner.FakeScannerManager
import com.picpocket.app.ui.screens.detail.DocumentDetailScreen
import com.picpocket.app.ui.screens.detail.DocumentDetailViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentDetailCollateTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = DocumentStore(app)
    private val repo = FakeDocumentRepository()

    private fun writeImage(bitmap: Bitmap): String {
        val file = File(app.cacheDir, "collate_${System.nanoTime()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Uri.fromFile(file).toString()
    }

    private fun launch(viewModel: DocumentDetailViewModel, id: String) {
        composeRule.setContent {
            MaterialTheme {
                DocumentDetailScreen(documentId = id, onNavigateBack = {}, onAddPage = {}, viewModel = viewModel)
            }
        }
        viewModel.loadDocument(id)
        composeRule.waitForIdle()
    }

    private fun viewModel() = DocumentDetailViewModel(
        app,
        repo,
        store,
        FakePdfGenerator(),
        OcrManager(FakeOcrEngine(), store),
        FakeScannerManager(),
    )

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun collateMergesSelectedPages() {
        val id = runBlocking { repo.createDocument("Collate Doc").getOrThrow() }
        val source = CollateFixtures.verticalSource(width = 40, height = 200, markerY = 100)
        runBlocking {
            repo.addPage(id, writeImage(CollateFixtures.cropVertical(source, fromY = 0, height = 120)))
            repo.addPage(id, writeImage(CollateFixtures.cropVertical(source, fromY = 80, height = 120)))
        }
        val vm = viewModel()
        launch(vm, id)

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Collate pages").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collate_select_all").assertIsOn()
        composeRule.onNodeWithTag("collate_preview").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.onAllNodesWithTag("collate_save").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("collate_save").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.getPages(id).getOrThrow().size } == 3
        }

        assertEquals(3, runBlocking { repo.getPages(id).getOrThrow().size })
    }

    @Test
    fun collateFailsVisiblyWithNoOverlap() {
        val id = runBlocking { repo.createDocument("Collate Doc").getOrThrow() }
        val black = Bitmap.createBitmap(40, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val white = Bitmap.createBitmap(40, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        runBlocking {
            repo.addPage(id, writeImage(black))
            repo.addPage(id, writeImage(white))
        }
        val vm = viewModel()
        launch(vm, id)

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Collate pages").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("collate_preview").performClick()

        waitForText("Could not align")
        assertEquals("no page must be added", 2, runBlocking { repo.getPages(id).getOrThrow().size })
    }

    @Test
    fun collateRemovesSourcesWhenChosen() {
        val id = runBlocking { repo.createDocument("Collate Doc").getOrThrow() }
        val source = CollateFixtures.verticalSource(width = 40, height = 200, markerY = 100)
        runBlocking {
            repo.addPage(id, writeImage(CollateFixtures.cropVertical(source, fromY = 0, height = 120)))
            repo.addPage(id, writeImage(CollateFixtures.cropVertical(source, fromY = 80, height = 120)))
        }
        val vm = viewModel()
        launch(vm, id)

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Collate pages").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("collate_remove_sources").assertIsOff()
        composeRule.onNodeWithTag("collate_remove_sources").performClick()
        composeRule.onNodeWithTag("collate_preview").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("collate_save").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("collate_save").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.getPages(id).getOrThrow().size } == 1
        }

        assertEquals(1, runBlocking { repo.getPages(id).getOrThrow().size })
    }
}
