package com.picpocket.app.domain.pdfimport

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads structure and embedded text from a PDF using PdfBox. Used to detect
 * born-digital PDFs (pages that carry real text) and to extract that text so a
 * native document is searchable without running OCR.
 */
@Singleton
class PdfStructure @Inject constructor() {

    fun pageCount(file: File): Int = try {
        PDDocument.load(file).use { it.numberOfPages }
    } catch (e: Exception) {
        0
    }

    fun pageText(file: File, index: Int): String = try {
        PDDocument.load(file).use { doc ->
            if (index < 0 || index >= doc.numberOfPages) return ""
            PDFTextStripper().apply {
                startPage = index + 1
                endPage = index + 1
                sortByPosition = true
            }.getText(doc).trim()
        }
    } catch (e: Exception) {
        ""
    }

    /** True when the PDF has at least one page with extractable text. */
    fun hasText(file: File): Boolean = try {
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            var extracted = false
            val last = doc.numberOfPages
            var page = 1
            while (page <= last && !extracted) {
                stripper.startPage = page
                stripper.endPage = page
                if (stripper.getText(doc).isNotBlank()) extracted = true
                page++
            }
            extracted
        }
    } catch (e: Exception) {
        false
    }

    /** Extracts each page's text in one pass (empty string for pages with no text). */
    fun pageTexts(file: File): List<String> = try {
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            (1..doc.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(doc).trim()
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
