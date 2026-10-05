package com.picpocket.app.domain.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.data.model.Page
import com.picpocket.app.domain.ocr.FakeLayoutOcrEngine
import com.picpocket.app.domain.ocr.OcrElement
import com.picpocket.app.domain.ocr.OcrLayoutResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchablePdfGeneratorTest {

    private lateinit var app: Context

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().putBoolean("searchable_pdf", true).apply()
    }

    private fun pageFile(index: Int): Page {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        val file = File(app.cacheDir, "page_$index.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return Page(
            id = index.toLong(),
            documentId = "doc",
            pageNumber = index,
            filename = file.name,
            imageUri = Uri.fromFile(file).toString(),
            createdAt = 0,
        )
    }

    private fun outFile(): File = File(app.cacheDir, "out_${System.nanoTime()}.pdf")

    private fun generate(engine: FakeLayoutOcrEngine, pages: List<Page>, out: File): PdfResult =
        runBlocking {
            SearchablePdfGenerator(engine).generate(app, pages, Uri.fromFile(out), PageSize.A4)
        }

    @Test
    fun exportsPdfWithPositionedTextLayer() {
        val engine = FakeLayoutOcrEngine(
            layout = OcrLayoutResult(
                text = "hello",
                elements = listOf(OcrElement("hello", 20, 20, 120, 60)),
            ),
        )
        val out = outFile()

        val result = generate(engine, listOf(pageFile(1)), out)

        assertTrue("expected success, was $result", result is PdfResult.Success)
        assertTrue(out.length() > 0)
        assertEquals(1, engine.layoutCalls)
    }

    @Test
    fun failingRecognizerStillExportsImages() {
        val out = outFile()

        val result = generate(FakeLayoutOcrEngine(fail = true), listOf(pageFile(1)), out)

        assertTrue("expected success, was $result", result is PdfResult.Success)
        assertTrue(out.length() > 0)
    }

    @Test
    fun pageWithNoElementsStillExports() {
        val engine = FakeLayoutOcrEngine(layout = OcrLayoutResult(text = "", elements = emptyList()))
        val out = outFile()

        val result = generate(engine, listOf(pageFile(1)), out)

        assertTrue("expected success, was $result", result is PdfResult.Success)
        assertTrue(out.length() > 0)
    }

    @Test
    fun searchableOffSkipsRecognition() {
        app.getSharedPreferences("settings", 0).edit().putBoolean("searchable_pdf", false).apply()
        val engine = FakeLayoutOcrEngine(
            layout = OcrLayoutResult("hello", listOf(OcrElement("hello", 0, 0, 10, 10))),
        )
        val out = outFile()

        val result = generate(engine, listOf(pageFile(1)), out)

        assertTrue("expected success, was $result", result is PdfResult.Success)
        assertEquals(0, engine.layoutCalls)
    }

    @Test
    fun multiPageExportRecognizesEachPage() {
        val engine = FakeLayoutOcrEngine(
            layout = OcrLayoutResult("hi", listOf(OcrElement("hi", 0, 0, 20, 20))),
        )
        val out = outFile()

        val result = generate(engine, listOf(pageFile(1), pageFile(2)), out)

        assertTrue("expected success, was $result", result is PdfResult.Success)
        assertEquals(2, engine.layoutCalls)
    }
}
