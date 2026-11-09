package com.picpocket.app.domain.workflow.engine

import android.app.Application
import android.net.Uri
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.export.PdfGenerator
import com.picpocket.app.domain.export.PdfResult
import com.picpocket.app.domain.storage.FolderAccess
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
    val folderAccess: FolderAccess,
    val pageSize: PageSize,
) {
    private val pdfMutex = Mutex()
    private var plainPdfCache: Artifact? = null
    private var redactedPdfCache: Artifact? = null

    val workDir: File
        get() = File(app.cacheDir, "workflow").apply { mkdirs() }

    /**
     * The artifact an action operates on: its input when it has one, otherwise
     * the triggering document rendered to a PDF. The engine renders the root
     * PDF eagerly, so this only falls back for a defensive null input.
     *
     * With [redact] the result is the redacted PDF: an already-redacted input is
     * passed through, a non-PDF input (zip/encrypted) is refused because the
     * document's areas are no longer reachable, and the document is rendered
     * with its areas removed otherwise.
     */
    suspend fun ensurePdf(input: Artifact?, redact: Boolean = false): Artifact {
        if (!redact) return input ?: renderPdf()
        if (input?.redacted == true) return input
        if (input != null && input.mime != PDF_MIME) {
            error("Remove redacted areas must run before zip or encrypt")
        }
        return renderPdf(redact = true)
    }

    /** Renders the document to a PDF once per run (plain and redacted) and caches it. */
    suspend fun renderPdf(redact: Boolean = false): Artifact {
        pdfMutex.withLock {
            val cached = if (redact) redactedPdfCache else plainPdfCache
            if (cached != null) return cached
            if (redact && !document.hasRedactions) {
                error("This document has no redacted areas")
            }
            val pages = repository.getPages(document.id).getOrNull().orEmpty()
            val name = if (redact) "${document.id}.redacted.pdf" else "${document.id}.pdf"
            val out = File(workDir, name)
            when (pdfGenerator.generate(app, pages, Uri.fromFile(out), pageSize, redact = redact)) {
                is PdfResult.Success -> Unit
                is PdfResult.Error -> throw IllegalStateException("PDF generation failed")
            }
            val artifact = Artifact(Uri.fromFile(out).toString(), PDF_MIME, redacted = redact)
            if (redact) redactedPdfCache = artifact else plainPdfCache = artifact
            return artifact
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
