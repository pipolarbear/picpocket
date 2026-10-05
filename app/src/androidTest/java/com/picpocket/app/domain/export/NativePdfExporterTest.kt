package com.picpocket.app.domain.export

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.PageKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativePdfExporterTest {

    private lateinit var app: Context

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(app)
    }

    private fun pdf(pages: Int): File {
        val file = File.createTempFile("src", ".pdf")
        PDDocument().use { doc ->
            repeat(pages) { index ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 18f)
                    content.newLineAtOffset(50f, 700f)
                    content.showText("Source page ${index + 1}")
                    content.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun nativePage(file: File, index: Int, number: Int): Page = Page(
        id = number.toLong(),
        documentId = "doc",
        pageNumber = number,
        filename = file.name,
        imageUri = Uri.fromFile(file).toString(),
        createdAt = 0,
        kind = PageKind.PDF,
        pdfPageIndex = index,
    )

    @Test
    fun unchangedSingleSourceIsCopiedByteForByte() {
        val source = pdf(pages = 2)
        val pages = listOf(nativePage(source, 0, 1), nativePage(source, 1, 2))
        val out = File.createTempFile("out", ".pdf")

        val ok = runBlocking {
            NativePdfExporter().export(app, pages, Uri.fromFile(out), copyWhenUnchanged = true)
        }

        assertTrue(ok)
        assertArrayEquals(source.readBytes(), out.readBytes())
    }

    @Test
    fun editedDocumentIsMergedInOrder() {
        val a = pdf(pages = 1)
        val b = pdf(pages = 1)
        val pages = listOf(nativePage(a, 0, 1), nativePage(b, 0, 2))
        val out = File.createTempFile("out", ".pdf")

        val ok = runBlocking {
            NativePdfExporter().export(app, pages, Uri.fromFile(out), copyWhenUnchanged = true)
        }

        assertTrue(ok)
        PDDocument.load(out).use { doc -> assertEquals(2, doc.numberOfPages) }
    }

    @Test
    fun unchangedDetectionRequiresOneContiguousSource() {
        val exporter = NativePdfExporter()
        val two = pdf(pages = 2)
        val a = pdf(pages = 1)
        val b = pdf(pages = 1)

        assertTrue(exporter.isUnchangedSingleSource(listOf(nativePage(two, 0, 1), nativePage(two, 1, 2))))
        assertFalse(exporter.isUnchangedSingleSource(listOf(nativePage(a, 0, 1), nativePage(b, 0, 2))))
    }
}
