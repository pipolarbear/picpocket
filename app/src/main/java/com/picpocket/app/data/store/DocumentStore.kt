package com.picpocket.app.data.store

import android.app.Application
import com.picpocket.app.data.model.PageKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class StoredDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: MutableList<StoredPage> = mutableListOf(),
    val tags: List<String> = emptyList(),
    val qualityTier: Int = 0,
    val ocrComplete: Boolean = false,
    val pageSize: String? = null,
    val syncExclude: Boolean = false,
)

@Serializable
data class StoredPage(
    val pageNumber: Int,
    val filename: String,
    val fileSizeBytes: Long = 0,
    val filterTypeOrdinal: Int = 0,
    val ocrText: String? = null,
    val createdAt: Long,
    val kind: PageKind = PageKind.IMAGE,
    val pdfPageIndex: Int = 0,
)

private fun StoredDocument.normalizedPages(): StoredDocument {
    val sequential = pages.withIndex().all { (index, page) -> page.pageNumber == index + 1 }
    if (sequential) return this
    return copy(
        pages = pages.mapIndexed { index, page -> page.copy(pageNumber = index + 1) }.toMutableList(),
    )
}

@Singleton
class DocumentStore @Inject constructor(
    private val app: Application,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Serializes every metadata read-modify-write so page numbering stays unique. */
    private val mutex = Mutex()

    private val documentsRoot: File
        get() = File(app.filesDir, "documents")

    fun documentDir(documentId: String): File =
        File(documentsRoot, documentId)

    fun pageFile(documentId: String, filename: String): File =
        File(documentDir(documentId), filename)

    fun pageFilenameFor(bytes: ByteArray): String =
        PageNaming.filenameFor(bytes)

    fun metadataFileName(documentId: String): String? {
        val dir = documentDir(documentId)
        val files = dir.listFiles() ?: return null
        return files.mapNotNull { f ->
            MetadataNaming.parse(f.name)?.let { vp -> f.name to vp }
        }.maxByOrNull { it.second.first }?.first
    }

    fun metadataVersion(documentId: String): Int {
        val name = metadataFileName(documentId) ?: return 0
        return MetadataNaming.parse(name)?.first ?: 0
    }

    fun metadataPassphrase(documentId: String): Int {
        val name = metadataFileName(documentId) ?: return 0
        return MetadataNaming.parse(name)?.second ?: 0
    }

    private fun decodeRaw(documentId: String): StoredDocument? {
        val name = metadataFileName(documentId) ?: return null
        val file = File(documentDir(documentId), name)
        return try {
            json.decodeFromString<StoredDocument>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Page numbers are always returned unique and sequential in stored order, so
     * a document that was written with duplicate numbers (older builds, or a bad
     * sync) can never reach the UI with colliding page keys.
     */
    suspend fun readMetadata(documentId: String): Result<StoredDocument> = withContext(Dispatchers.IO) {
        val doc = decodeRaw(documentId)
            ?: return@withContext Result.failure(Exception("Document not found: $documentId"))
        Result.success(doc.normalizedPages())
    }

    suspend fun writeMetadata(documentId: String, doc: StoredDocument): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeMetadataTo(documentId, doc, metadataVersion(documentId) + 1, metadataPassphrase(documentId))
        }
    }

    suspend fun writeMetadataAt(documentId: String, doc: StoredDocument, version: Int, passphrase: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock { writeMetadataTo(documentId, doc, version, passphrase) }
        }

    private fun writeMetadataTo(documentId: String, doc: StoredDocument, version: Int, passphrase: Int): Result<Unit> {
        return try {
            val dir = documentDir(documentId)
            dir.mkdirs()
            val name = MetadataNaming.name(version, passphrase)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(json.encodeToString(doc))
            tmp.renameTo(File(dir, name))
            for (f in dir.listFiles() ?: emptyArray()) {
                if (f.name != name && MetadataNaming.isMetadata(f.name)) f.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun createDocument(
        name: String,
        qualityTier: Int = 0,
        pageSize: String? = null,
    ): Result<StoredDocument> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val doc = StoredDocument(
                id = id,
                name = name,
                createdAt = now,
                updatedAt = now,
                qualityTier = qualityTier,
                pageSize = pageSize,
            )
            writeMetadataAt(id, doc, 0, 0).getOrElse { return@withContext Result.failure(it) }
            Result.success(doc)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun listDocuments(): Result<List<StoredDocument>> = withContext(Dispatchers.IO) {
        try {
            val root = documentsRoot
            if (!root.exists()) return@withContext Result.success(emptyList())
            val docs = root.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir -> readMetadata(dir.name).getOrNull() }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
            Result.success(docs)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun deleteDocument(documentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            documentDir(documentId).deleteRecursively()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Rewrites the metadata of every document whose page numbers are not unique
     * and sequential, so corruption from older builds or a bad sync is repaired
     * on disk at startup. Returns how many documents were fixed.
     */
    suspend fun repairPageNumbers(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val root = documentsRoot
            if (!root.exists()) return@withLock 0
            var repaired = 0
            for (dir in root.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
                val raw = decodeRaw(dir.name) ?: continue
                val fixed = raw.normalizedPages()
                if (fixed !== raw) {
                    writeMetadataTo(dir.name, fixed, metadataVersion(dir.name) + 1, metadataPassphrase(dir.name))
                    repaired++
                }
            }
            repaired
        }
    }

    private fun MutableList<StoredPage>.nextNumber(): Int =
        (maxOfOrNull { it.pageNumber } ?: 0) + 1

    /**
     * Appends a page with a guaranteed-unique number (assigned inside the lock)
     * and returns it. This is the only path new pages should use.
     */
    suspend fun appendPage(
        documentId: String,
        filename: String,
        fileSizeBytes: Long,
        filterTypeOrdinal: Int = 0,
        createdAt: Long = System.currentTimeMillis(),
        kind: PageKind = PageKind.IMAGE,
        pdfPageIndex: Int = 0,
        ocrText: String? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            val pageNumber = doc.pages.nextNumber()
            doc.pages.add(
                StoredPage(
                    pageNumber = pageNumber,
                    filename = filename,
                    fileSizeBytes = fileSizeBytes,
                    filterTypeOrdinal = filterTypeOrdinal,
                    createdAt = createdAt,
                    kind = kind,
                    pdfPageIndex = pdfPageIndex,
                    ocrText = ocrText,
                ),
            )
            writeMetadataTo(
                documentId,
                doc.copy(updatedAt = System.currentTimeMillis(), pages = doc.pages),
                metadataVersion(documentId) + 1,
                metadataPassphrase(documentId),
            ).getOrElse { return@withLock Result.failure(it) }
            Result.success(pageNumber)
        }
    }

    /**
     * Appends a page at an explicit number, reassigning it when that number is
     * already taken so a document never ends up with duplicate page numbers.
     */
    suspend fun addPage(
        documentId: String,
        pageNumber: Int,
        filename: String,
        fileSizeBytes: Long,
        filterTypeOrdinal: Int = 0,
        createdAt: Long = System.currentTimeMillis(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            val assigned = if (doc.pages.any { it.pageNumber == pageNumber }) doc.pages.nextNumber() else pageNumber
            doc.pages.add(
                StoredPage(
                    pageNumber = assigned,
                    filename = filename,
                    fileSizeBytes = fileSizeBytes,
                    filterTypeOrdinal = filterTypeOrdinal,
                    createdAt = createdAt,
                ),
            )
            writeMetadataTo(
                documentId,
                doc.copy(updatedAt = System.currentTimeMillis(), pages = doc.pages),
                metadataVersion(documentId) + 1,
                metadataPassphrase(documentId),
            )
        }
    }

    suspend fun removePage(documentId: String, pageNumber: Int): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            val removed = doc.pages.filter { it.pageNumber == pageNumber }
            doc.pages.removeAll { it.pageNumber == pageNumber }
            renumberPages(doc)
            writeMetadataTo(
                documentId,
                doc.copy(updatedAt = System.currentTimeMillis()),
                metadataVersion(documentId) + 1,
                metadataPassphrase(documentId),
            ).getOrElse { return@withLock Result.failure(it) }
            deleteUnreferenced(documentId, removed, doc.pages)
            Result.success(Unit)
        }
    }

    suspend fun reorderPages(documentId: String, orderedPageNumbers: List<Int>): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            val byNumber = doc.pages.associateBy { it.pageNumber }
            val reordered = orderedPageNumbers.mapNotNull { byNumber[it] }
            // Fall back to stored order if the caller's numbers did not cover the pages.
            val ordered = if (reordered.size == doc.pages.size) reordered else doc.pages
            val updated = ordered.mapIndexed { index, page -> page.copy(pageNumber = index + 1) }
            writeMetadataTo(
                documentId,
                doc.copy(pages = updated.toMutableList(), updatedAt = System.currentTimeMillis()),
                metadataVersion(documentId) + 1,
                metadataPassphrase(documentId),
            )
        }
    }

    suspend fun updatePageOcrText(documentId: String, pageNumber: Int, ocrText: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            val idx = doc.pages.indexOfFirst { it.pageNumber == pageNumber }
            if (idx < 0) return@withLock Result.failure(Exception("Page $pageNumber not found"))
            doc.pages[idx] = doc.pages[idx].copy(ocrText = ocrText)
            writeMetadataTo(documentId, doc.copy(pages = doc.pages), metadataVersion(documentId) + 1, metadataPassphrase(documentId))
        }
    }

    suspend fun updateDocumentName(documentId: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            writeMetadataTo(
                documentId,
                doc.copy(name = name, updatedAt = System.currentTimeMillis()),
                metadataVersion(documentId) + 1,
                metadataPassphrase(documentId),
            )
        }
    }

    suspend fun nextPageNumber(documentId: String): Result<Int> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        Result.success(doc.pages.nextNumber())
    }

    suspend fun replacePageImage(
        documentId: String,
        pageNumber: Int,
        filename: String,
        fileSizeBytes: Long,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            val idx = doc.pages.indexOfFirst { it.pageNumber == pageNumber }
            if (idx < 0) return@withLock Result.failure(Exception("Page $pageNumber not found"))
            doc.pages[idx] = doc.pages[idx].copy(
                filename = filename,
                fileSizeBytes = fileSizeBytes,
                ocrText = null,
            )
            writeMetadataTo(
                documentId,
                doc.copy(
                    pages = doc.pages,
                    ocrComplete = false,
                    updatedAt = System.currentTimeMillis(),
                ),
                metadataVersion(documentId) + 1,
                metadataPassphrase(documentId),
            )
        }
    }

    suspend fun totalFileSize(documentId: String): Result<Long> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        Result.success(doc.pages.sumOf { it.fileSizeBytes })
    }

    suspend fun replacePages(documentId: String, keptFilenames: List<String>): Result<List<String>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            val kept = keptFilenames.mapNotNull { filename ->
                doc.pages.find { it.filename == filename }
            }
            val keptSet = keptFilenames.toSet()
            val removed = doc.pages.filter { it.filename !in keptSet }

            if (kept.isEmpty()) {
                deleteDocument(documentId)
                return@withLock Result.success(removed.map { it.filename })
            }

            val updated = kept.mapIndexed { index, page ->
                page.copy(pageNumber = index + 1)
            }
            val writeResult = writeMetadataTo(
                documentId,
                doc.copy(pages = updated.toMutableList(), updatedAt = System.currentTimeMillis()),
                metadataVersion(documentId) + 1,
                metadataPassphrase(documentId),
            )
            writeResult.getOrElse { return@withLock Result.failure(it) }
            deleteUnreferenced(documentId, removed, updated)
            Result.success(removed.map { it.filename })
        }
    }

    /**
     * Adds the merged page and, when [removeSources], removes the source pages in
     * a single metadata write. The merged page takes the position of the first
     * source so reading order is preserved. `ocrComplete` is recomputed so a page
     * without text is picked up by OCR later.
     */
    suspend fun insertMergedPage(
        documentId: String,
        mergedFilename: String,
        mergedFileSize: Long,
        sourcePageNumbers: List<Int>,
        removeSources: Boolean,
        mergedOcrText: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            val sources = doc.pages.filter { it.pageNumber in sourcePageNumbers }
            val firstSourceIndex = doc.pages.indexOfFirst { it.pageNumber in sourcePageNumbers }
            val insertIndex = if (firstSourceIndex < 0) doc.pages.size else firstSourceIndex
            val kept = if (removeSources) doc.pages.filter { it.pageNumber !in sourcePageNumbers } else doc.pages.toList()
            val merged = StoredPage(
                pageNumber = 0,
                filename = mergedFilename,
                fileSizeBytes = mergedFileSize,
                ocrText = mergedOcrText,
                createdAt = System.currentTimeMillis(),
            )
            val result = kept.toMutableList().apply { add(insertIndex.coerceIn(0, size), merged) }
            result.forEachIndexed { index, page -> result[index] = page.copy(pageNumber = index + 1) }
            writeMetadataTo(
                documentId,
                doc.copy(
                    pages = result,
                    ocrComplete = result.all { it.ocrText != null },
                    updatedAt = System.currentTimeMillis(),
                ),
                metadataVersion(documentId) + 1,
                metadataPassphrase(documentId),
            ).getOrElse { return@withLock Result.failure(it) }
            if (removeSources) {
                deleteUnreferenced(documentId, sources, result)
            }
            Result.success(Unit)
        }
    }

    suspend fun updateSyncExclude(documentId: String, excluded: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            writeMetadataTo(documentId, doc.copy(syncExclude = excluded), metadataVersion(documentId) + 1, metadataPassphrase(documentId))
        }
    }

    /** Recomputes `ocrComplete` from whether every page has text. */
    suspend fun refreshOcrComplete(documentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            writeMetadataTo(
                documentId,
                doc.copy(ocrComplete = doc.pages.isNotEmpty() && doc.pages.all { it.ocrText != null }),
                metadataVersion(documentId) + 1,
                metadataPassphrase(documentId),
            )
        }
    }

    /** Deletes a page file only when no page still references it (shared PDF sources). */
    suspend fun deleteFileIfUnreferenced(documentId: String, filename: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val doc = readMetadata(documentId).getOrElse { return@withLock Result.failure(it) }
            if (doc.pages.none { it.filename == filename }) {
                pageFile(documentId, filename).delete()
            }
            Result.success(Unit)
        }
    }

    private fun deleteUnreferenced(
        documentId: String,
        candidates: List<StoredPage>,
        remaining: List<StoredPage>,
    ) {
        val referenced = remaining.map { it.filename }.toSet()
        for (page in candidates) {
            if (page.filename !in referenced) pageFile(documentId, page.filename).delete()
        }
    }

    private fun renumberPages(doc: StoredDocument) {
        for ((i, page) in doc.pages.withIndex()) {
            doc.pages[i] = page.copy(pageNumber = i + 1)
        }
    }
}
