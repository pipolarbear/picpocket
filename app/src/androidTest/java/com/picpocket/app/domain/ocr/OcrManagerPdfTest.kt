package com.picpocket.app.domain.ocr

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.data.model.PageKind
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.domain.render.PageRenderer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrManagerPdfTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun runOcrRendersANativePdfPageBeforeRecognizing() = runBlocking {
        val store = DocumentStore(ApplicationProvider.getApplicationContext<Application>())
        val doc = store.createDocument("Doc").getOrThrow()
        val pdfFile = File(store.documentDir(doc.id), "source.pdf")
        PDDocument().use { pd ->
            val page = PDPage()
            pd.addPage(page)
            PDPageContentStream(pd, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 18f)
                content.newLineAtOffset(50f, 700f)
                content.showText("recognize me")
                content.endText()
            }
            pd.save(pdfFile)
        }
        store.appendPage(doc.id, "source.pdf", pdfFile.length(), kind = PageKind.PDF, pdfPageIndex = 0)

        val engine = FakeOcrEngine().apply { returnedText = "OCR text" }
        OcrManager(engine, store, PageRenderer()).runOcr(doc.id)

        val page = store.readMetadata(doc.id).getOrThrow().pages.first()
        assertEquals("OCR text", page.ocrText)
    }
}
