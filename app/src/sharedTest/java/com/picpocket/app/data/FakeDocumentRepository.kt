package com.picpocket.app.data

import android.net.Uri
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.Tag
import com.picpocket.app.data.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class FakeDocumentRepository : DocumentRepository {

    private val documents = MutableStateFlow<List<Document>>(emptyList())
    private val pageLists = mutableMapOf<DocumentId, MutableStateFlow<List<Page>>>()
    private val pageFilenames = mutableMapOf<DocumentId, MutableStateFlow<Map<Long, String>>>()
    private val tags = MutableStateFlow<List<Tag>>(emptyList())
    private val documentTags = mutableMapOf<DocumentId, MutableStateFlow<List<Tag>>>()
    private val documentTagMap = MutableStateFlow<Map<DocumentId, List<Tag>>>(emptyMap())
    private val lastAccessed = MutableStateFlow<Map<DocumentId, Long>>(emptyMap())
    private var nextDocId = 0
    private var nextTagId = 1L

    var failDeleteDocument = false

    /** Test helper: set a deterministic "last seen" time. */
    fun setLastAccessed(id: DocumentId, at: Long) {
        lastAccessed.value = lastAccessed.value + (id to at)
    }

    private fun withAccess(doc: Document): Document =
        doc.copy(lastAccessedAt = lastAccessed.value[doc.id] ?: doc.updatedAt)

    override fun observeDocuments(): Flow<List<Document>> =
        combine(documents, lastAccessed) { docs, access ->
            docs.map { it.copy(lastAccessedAt = access[it.id] ?: it.updatedAt) }
        }

    override fun observeDocument(documentId: DocumentId): Flow<Document?> =
        observeDocuments().map { list -> list.find { it.id == documentId } }

    override fun observePages(documentId: DocumentId): Flow<List<Page>> {
        return pageLists.getOrPut(documentId) { MutableStateFlow(emptyList()) }
    }

    override suspend fun getDocument(documentId: DocumentId): Result<Document> {
        val doc = documents.value.find { it.id == documentId }
        return if (doc != null) Result.success(withAccess(doc)) else Result.failure(Exception("Document not found"))
    }

    fun seedDocument(id: DocumentId, name: String, ocrComplete: Boolean = false) {
        val now = System.currentTimeMillis()
        val doc = Document(id, name, now, now, qualityTier = 0, pageSize = null, ocrComplete = ocrComplete)
        documents.value = documents.value + doc
    }

    override suspend fun getPages(documentId: DocumentId): Result<List<Page>> {
        return Result.success(pageLists[documentId]?.value ?: emptyList())
    }

    override suspend fun createDocument(name: String, qualityTier: Int, pageSize: String?): Result<DocumentId> {
        val now = System.currentTimeMillis()
        nextDocId++
        val id = "doc_$nextDocId"
        val doc = Document(id, name, now, now, qualityTier = qualityTier, pageSize = pageSize)
        documents.value = documents.value + doc
        return Result.success(id)
    }

    override suspend fun addPage(
        documentId: DocumentId,
        imageUri: String,
        filterTypeOrdinal: Int,
        fileSizeBytes: Long,
        qualityTier: Int,
    ): Result<Unit> {
        val now = System.currentTimeMillis()
        val existingPages = pageLists.getOrPut(documentId) { MutableStateFlow(emptyList()) }
        val pageNum = existingPages.value.size + 1
        val filename = "%05d".format(pageNum)
        val page = Page(
            id = pageNum.toLong(),
            documentId = documentId,
            pageNumber = pageNum,
            filename = filename,
            imageUri = imageUri,
            ocrText = null,
            filterTypeOrdinal = filterTypeOrdinal,
            createdAt = now,
        )
        existingPages.value = existingPages.value + page
        val filenames = pageFilenames.getOrPut(documentId) { MutableStateFlow(emptyMap()) }
        filenames.value = filenames.value + (page.id to filename)
        return Result.success(Unit)
    }

    override suspend fun collatePages(
        documentId: DocumentId,
        sourcePageNumbers: List<Int>,
        mergedImageUri: String,
        removeSources: Boolean,
        qualityTier: Int,
    ): Result<Unit> {
        val state = pageLists.getOrPut(documentId) { MutableStateFlow(emptyList()) }
        val pages = state.value.sortedBy { it.pageNumber }
        val sourceTexts = pages.filter { it.pageNumber in sourcePageNumbers }
            .sortedBy { it.pageNumber }
            .map { it.ocrText }
        val inherited = if (sourceTexts.isNotEmpty() && sourceTexts.all { it != null }) {
            sourceTexts.filterNotNull().joinToString("\n")
        } else {
            null
        }
        val firstIndex = pages.indexOfFirst { it.pageNumber in sourcePageNumbers }
        val insertIndex = if (firstIndex < 0) pages.size else firstIndex
        val kept = if (removeSources) pages.filter { it.pageNumber !in sourcePageNumbers } else pages
        val next = (kept.maxOfOrNull { it.pageNumber } ?: 0) + 1
        val merged = Page(
            id = next.toLong(),
            documentId = documentId,
            pageNumber = 0,
            filename = "merged_${next}.jpg",
            imageUri = mergedImageUri,
            ocrText = inherited,
            createdAt = System.currentTimeMillis(),
        )
        state.value = kept.toMutableList()
            .apply { add(insertIndex.coerceIn(0, size), merged) }
            .mapIndexed { index, page -> page.copy(pageNumber = index + 1) }
        return Result.success(Unit)
    }

    override suspend fun updatePageOcrText(documentId: DocumentId, pageNumber: Int, ocrText: String): Result<Unit> {
        val state = pageLists[documentId] ?: return Result.success(Unit)
        state.value = state.value.map {
            if (it.pageNumber == pageNumber) it.copy(ocrText = ocrText) else it
        }
        return Result.success(Unit)
    }

    override suspend fun updateDocumentName(documentId: DocumentId, name: String): Result<Unit> {
        documents.value = documents.value.map {
            if (it.id == documentId) it.copy(name = name) else it
        }
        return Result.success(Unit)
    }

    override suspend fun getDocumentsByName(name: String): Result<List<Document>> {
        return Result.success(documents.value.filter { it.name == name })
    }

    override suspend fun getAllDocuments(): Result<List<Document>> {
        return Result.success(documents.value.map { withAccess(it) })
    }

    override suspend fun markDocumentAccessed(documentId: DocumentId) {
        lastAccessed.value = lastAccessed.value + (documentId to System.currentTimeMillis())
    }

    override suspend fun deleteDocumentsByName(name: String): Result<Unit> {
        val ids = documents.value.filter { it.name == name }.map { it.id }
        return deleteDocuments(ids)
    }

    override suspend fun deleteDocuments(documentIds: List<DocumentId>): Result<Unit> {
        documents.value = documents.value.filter { it.id !in documentIds }
        for (id in documentIds) {
            pageLists.remove(id)
        }
        return Result.success(Unit)
    }

    override suspend fun deleteDocument(documentId: DocumentId): Result<Unit> {
        if (failDeleteDocument) return Result.failure(Exception("delete failed"))
        return deleteDocuments(listOf(documentId))
    }

    override suspend fun deletePage(documentId: DocumentId, pageNumber: Int): Result<Unit> {
        val state = pageLists[documentId] ?: return Result.success(Unit)
        state.value = state.value.filter { it.pageNumber != pageNumber }
        val filenames = pageFilenames[documentId]
        if (filenames != null) {
            val removed = filenames.value.filter { it.value == "%05d".format(pageNumber) }
            filenames.value = filenames.value - removed.keys
        }
        return Result.success(Unit)
    }

    override suspend fun replacePages(documentId: DocumentId, keptFilenames: List<String>): Result<Unit> {
        val state = pageLists[documentId] ?: return Result.success(Unit)
        val filenames = pageFilenames[documentId] ?: return Result.success(Unit)
        val keptSet = keptFilenames.toSet()
        val keptIds = filenames.value.filter { it.value in keptSet }.keys
        val survivors = state.value.filter { it.id in keptIds }
        val newFilenames = survivors.mapIndexed { index, page ->
            page.id to "%05d".format(index + 1)
        }.toMap()
        val updated = survivors.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        state.value = updated
        filenames.value = newFilenames
        return Result.success(Unit)
    }

    override suspend fun reorderPages(documentId: DocumentId, pageNumbers: List<Int>): Result<Unit> {
        val state = pageLists[documentId] ?: return Result.success(Unit)
        val pageMap = state.value.associateBy { it.pageNumber }
        val reordered = pageNumbers.mapNotNull { pageMap[it] }
        val updated = reordered.mapIndexed { index, page ->
            page.copy(pageNumber = index + 1)
        }
        state.value = updated
        return Result.success(Unit)
    }

    override suspend fun searchDocumentsByOcrText(query: String): Result<Set<DocumentId>> {
        val regex = try { Regex(query, RegexOption.IGNORE_CASE) } catch (_: Exception) { return Result.success(emptySet()) }
        val result = pageLists.entries.flatMap { (docId, state) ->
            state.value.filter { it.ocrText != null && regex.containsMatchIn(it.ocrText!!) }
                .map { docId }
        }.toSet()
        return Result.success(result)
    }

    var failImportPdf = false

    override suspend fun importPdf(uri: Uri): Result<DocumentId> {
        if (failImportPdf) return Result.failure(Exception("Import failed"))
        val id = createDocument("Imported PDF").getOrThrow()
        return Result.success(id)
    }

    var failRescanPage = false
    var rescanCallCount = 0

    override suspend fun rescanPage(documentId: DocumentId, pageNumber: Int, imageUri: String): Result<Unit> {
        if (failRescanPage) return Result.failure(Exception("Rescan failed"))
        rescanCallCount++
        val state = pageLists[documentId] ?: return Result.failure(Exception("Document not found"))
        state.value = state.value.map {
            if (it.pageNumber == pageNumber) it.copy(imageUri = imageUri, ocrText = null) else it
        }
        return Result.success(Unit)
    }

    override fun observeAllTags(): Flow<List<Tag>> = tags

    override fun observeDocumentTags(documentId: DocumentId): Flow<List<Tag>> {
        return documentTags.getOrPut(documentId) { MutableStateFlow(emptyList()) }
    }

    override fun observeDocumentTagMap(): Flow<Map<DocumentId, List<Tag>>> = documentTagMap

    override fun searchTags(query: String): Flow<List<Tag>> {
        return tags.map { list -> list.filter { it.name.contains(query, ignoreCase = true) } }
    }

    override fun notifyDocumentsChanged() {}

    override suspend fun createTag(name: String): Long {
        val id = nextTagId++
        val color = ((tags.value.size) % 8)
        val tag = Tag(id, name, color)
        tags.value = tags.value + tag
        return id
    }

    override suspend fun renameTag(tagId: Long, name: String) {
        tags.value = tags.value.map { if (it.id == tagId) it.copy(name = name) else it }
    }

    override suspend fun deleteTags(tagIds: List<Long>) {
        tags.value = tags.value.filter { it.id !in tagIds }
        for ((_, state) in documentTags) {
            state.value = state.value.filter { it.id !in tagIds }
        }
        val map = mutableMapOf<DocumentId, List<Tag>>()
        for ((id, state) in documentTags) {
            map[id] = state.value
        }
        documentTagMap.value = map
    }

    override suspend fun setDocumentTags(documentId: DocumentId, tagIds: List<Long>) {
        val selected = tags.value.filter { it.id in tagIds }
        documentTags.getOrPut(documentId) { MutableStateFlow(emptyList()) }.value = selected
        val map = mutableMapOf<DocumentId, List<Tag>>()
        for ((id, state) in documentTags) {
            map[id] = state.value
        }
        documentTagMap.value = map
    }
}
