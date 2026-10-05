package com.picpocket.app.domain.export

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.PageKind
import com.picpocket.app.domain.ocr.FakeLayoutOcrEngine
import com.picpocket.app.domain.render.PageRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoutingPdfGeneratorTest {

    private lateinit var app: Context

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun returnsErrorWhenNativeExportAndRasterizeBothFail() {
        val missing = File(app.cacheDir, "missing_${System.nanoTime()}.pdf")
        val page = Page(
            id = 1,
            documentId = "doc",
            pageNumber = 1,
            filename = missing.name,
            imageUri = Uri.fromFile(missing).toString(),
            createdAt = 0,
            kind = PageKind.PDF,
            pdfPageIndex = 0,
        )
        val out = File.createTempFile("out", ".pdf")
        val generator = RoutingPdfGenerator(
            SearchablePdfGenerator(FakeLayoutOcrEngine()),
            NativePdfExporter(),
            PageRenderer(),
        )

        val result = runBlocking {
            generator.generate(app, listOf(page), Uri.fromFile(out), PageSize.A4)
        }

        assertTrue("expected an error result, was $result", result is PdfResult.Error)
    }
}
