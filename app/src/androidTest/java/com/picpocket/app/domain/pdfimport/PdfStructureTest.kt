package com.picpocket.app.domain.pdfimport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfStructureTest {

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    private fun textPdf(pages: Int): File {
        val file = File.createTempFile("text", ".pdf")
        PDDocument().use { doc ->
            repeat(pages) { index ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 18f)
                    content.newLineAtOffset(50f, 700f)
                    content.showText("Page ${index + 1} instructions")
                    content.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun blankPdf(pages: Int): File {
        val file = File.createTempFile("blank", ".pdf")
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            doc.save(file)
        }
        return file
    }

    @Test
    fun detectsTextAndExtractsEachPage() {
        val structure = PdfStructure()
        val file = textPdf(pages = 2)

        assertTrue(structure.hasText(file))
        assertEquals(2, structure.pageCount(file))
        val texts = structure.pageTexts(file)
        assertEquals(2, texts.size)
        assertTrue(texts[0].contains("Page 1"))
        assertTrue(texts[1].contains("Page 2"))
    }

    @Test
    fun blankPdfHasNoText() {
        val structure = PdfStructure()
        val file = blankPdf(pages = 2)

        assertFalse(structure.hasText(file))
        assertTrue(structure.pageTexts(file).all { it.isBlank() })
    }
}
