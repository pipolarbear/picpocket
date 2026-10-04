package com.picpocket.app.data.store

import android.app.Application
import kotlinx.coroutines.Dispatchers
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
)

@Singleton
class DocumentStore @Inject constructor(
    private val app: Application,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

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

    suspend fun readMetadata(documentId: String): Result<StoredDocument> = withContext(Dispatchers.IO) {
        val name = metadataFileName(documentId)
        if (name == null) return@withContext Result.failure(Exception("Document not found: $documentId"))
        val file = File(documentDir(documentId), name)
        try {
            Result.success(json.decodeFromString<StoredDocument>(file.readText()))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun writeMetadata(documentId: String, doc: StoredDocument): Result<Unit> = withContext(Dispatchers.IO) {
        writeMetadataTo(documentId, doc, metadataVersion(documentId) + 1, metadataPassphrase(documentId))
    }

    suspend fun writeMetadataAt(documentId: String, doc: StoredDocument, version: Int, passphrase: Int): Result<Unit> = withContext(Dispatchers.IO) {
        writeMetadataTo(documentId, doc, version, passphrase)
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

    suspend fun addPage(
        documentId: String,
        pageNumber: Int,
        filename: String,
        fileSizeBytes: Long,
        filterTypeOrdinal: Int = 0,
        createdAt: Long = System.currentTimeMillis(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        doc.pages.add(
            StoredPage(
                pageNumber = pageNumber,
                filename = filename,
                fileSizeBytes = fileSizeBytes,
                filterTypeOrdinal = filterTypeOrdinal,
                createdAt = createdAt,
            )
        )
        val newDoc = doc.copy(updatedAt = System.currentTimeMillis(), pages = doc.pages)
        writeMetadata(documentId, newDoc)
        Result.success(Unit)
    }

    suspend fun removePage(documentId: String, pageNumber: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        val removed = doc.pages.filter { it.pageNumber == pageNumber }
        doc.pages.removeAll { it.pageNumber == pageNumber }
        renumberPages(doc)
        writeMetadata(documentId, doc.copy(updatedAt = System.currentTimeMillis()))
        for (page in removed) {
            pageFile(documentId, page.filename).delete()
        }
        Result.success(Unit)
    }

    suspend fun reorderPages(documentId: String, orderedPageNumbers: List<Int>): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        val pageMap = doc.pages.associateBy { it.pageNumber }
        val reordered = orderedPageNumbers.mapNotNull { pageMap[it] }
        val updated = reordered.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        writeMetadata(documentId, doc.copy(pages = updated.toMutableList(), updatedAt = System.currentTimeMillis()))
        Result.success(Unit)
    }

    suspend fun updatePageOcrText(documentId: String, pageNumber: Int, ocrText: String): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        val idx = doc.pages.indexOfFirst { it.pageNumber == pageNumber }
        if (idx < 0) return@withContext Result.failure(Exception("Page $pageNumber not found"))
        doc.pages[idx] = doc.pages[idx].copy(ocrText = ocrText)
        writeMetadata(documentId, doc.copy(pages = doc.pages))
        Result.success(Unit)
    }

    suspend fun updateDocumentName(documentId: String, name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        writeMetadata(documentId, doc.copy(name = name, updatedAt = System.currentTimeMillis()))
        Result.success(Unit)
    }

    suspend fun nextPageNumber(documentId: String): Result<Int> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        Result.success((doc.pages.maxOfOrNull { it.pageNumber } ?: 0) + 1)
    }

    suspend fun replacePageImage(
        documentId: String,
        pageNumber: Int,
        filename: String,
        fileSizeBytes: Long,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        val idx = doc.pages.indexOfFirst { it.pageNumber == pageNumber }
        if (idx < 0) return@withContext Result.failure(Exception("Page $pageNumber not found"))
        doc.pages[idx] = doc.pages[idx].copy(
            filename = filename,
            fileSizeBytes = fileSizeBytes,
            ocrText = null,
        )
        writeMetadata(documentId, doc.copy(
            pages = doc.pages,
            ocrComplete = false,
            updatedAt = System.currentTimeMillis(),
        ))
        Result.success(Unit)
    }

    suspend fun totalFileSize(documentId: String): Result<Long> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        Result.success(doc.pages.sumOf { it.fileSizeBytes })
    }

    suspend fun replacePages(documentId: String, keptFilenames: List<String>): Result<List<String>> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        val kept = keptFilenames.mapNotNull { filename ->
            doc.pages.find { it.filename == filename }
        }
        val keptSet = keptFilenames.toSet()
        val removed = doc.pages.filter { it.filename !in keptSet }

        if (kept.isEmpty()) {
            deleteDocument(documentId)
            return@withContext Result.success(removed.map { it.filename })
        }

        val updated = kept.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        val writeResult = writeMetadata(documentId, doc.copy(pages = updated.toMutableList(), updatedAt = System.currentTimeMillis()))
        writeResult.getOrElse { return@withContext Result.failure(it) }
        Result.success(removed.map { it.filename })
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
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
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
        writeMetadata(
            documentId,
            doc.copy(
                pages = result,
                ocrComplete = result.all { it.ocrText != null },
                updatedAt = System.currentTimeMillis(),
            ),
        ).getOrElse { return@withContext Result.failure(it) }
        if (removeSources) {
            for (page in sources) pageFile(documentId, page.filename).delete()
        }
        Result.success(Unit)
    }

    suspend fun updateSyncExclude(documentId: String, excluded: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = readMetadata(documentId).getOrElse { return@withContext Result.failure(it) }
        writeMetadata(documentId, doc.copy(syncExclude = excluded))
    }

    private fun renumberPages(doc: StoredDocument) {
        doc.pages.sortBy { it.pageNumber }
        for ((i, page) in doc.pages.withIndex()) {
            doc.pages[i] = page.copy(pageNumber = i + 1)
        }
    }
}
