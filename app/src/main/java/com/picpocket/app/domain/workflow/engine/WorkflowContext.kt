package com.picpocket.app.domain.workflow.engine

import android.app.Application
import android.net.Uri
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.export.PdfGenerator
import com.picpocket.app.domain.export.PdfResult
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.model.Artifact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Everything an action needs for one run: the triggering document, the service
 * handles, and helpers to materialise a PDF artifact.
 */
class WorkflowContext(
    val document: Document,
    val app: Application,
    val repository: DocumentRepository,
    val pdfGenerator: PdfGenerator,
    val cipher: ArtifactCipher,
    val foreground: ForegroundExecutor,
    val pageSize: PageSize,
) {
    private val pdfMutex = Mutex()
    private var pdfCache: Artifact? = null

    val workDir: File
        get() = File(app.cacheDir, "workflow").apply { mkdirs() }

    /**
     * The artifact an action operates on: its input when it has one, otherwise
     * the triggering document rendered to a PDF. The engine renders the root
     * PDF eagerly, so this only falls back for a defensive null input.
     */
    suspend fun ensurePdf(input: Artifact?): Artifact = input ?: renderPdf()

    /** Renders the document to a PDF once per run and caches it. */
    suspend fun renderPdf(): Artifact {
        pdfMutex.withLock {
            pdfCache?.let { return it }
            val pages = repository.getPages(document.id).getOrNull().orEmpty()
            val out = File(workDir, "${document.id}.pdf")
            when (pdfGenerator.generate(app, pages, Uri.fromFile(out), pageSize)) {
                is PdfResult.Success -> Unit
                is PdfResult.Error -> throw IllegalStateException("PDF generation failed")
            }
            return Artifact(Uri.fromFile(out).toString(), PDF_MIME).also { pdfCache = it }
        }
    }

    suspend fun hasTag(tagId: Long): Boolean =
        repository.observeDocumentTags(document.id).first().any { it.id == tagId }

    suspend fun ocrText(): String =
        repository.getPages(document.id).getOrNull().orEmpty()
            .mapNotNull { it.ocrText }
            .joinToString(" ")

    companion object {
        const val PDF_MIME = "application/pdf"
    }
}
