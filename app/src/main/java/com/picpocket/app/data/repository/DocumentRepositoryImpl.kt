package com.picpocket.app.data.repository

import com.picpocket.app.data.local.dao.TagDao
import com.picpocket.app.data.local.entity.TagEntity
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.PageKind
import com.picpocket.app.data.model.Tag
import com.picpocket.app.data.store.DocumentAccessStore
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.PageNaming
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.data.workflow.DocumentEvent
import com.picpocket.app.data.workflow.DocumentEventBus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.pdfimport.PdfPageImporter
import com.picpocket.app.domain.pdfimport.PdfStructure
import com.picpocket.app.domain.ocr.OcrManager
import com.picpocket.app.domain.scan.PageEncoder
import com.picpocket.app.domain.scan.QualityTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val store: DocumentStore,
    private val tagDao: TagDao,
    private val pdfPageImporter: PdfPageImporter,
    private val pdfStructure: PdfStructure,
    private val ocrManager: OcrManager,
    private val app: Application,
    private val eventBus: DocumentEventBus,
    private val documentAccess: DocumentAccessStore,
) : DocumentRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _documents = MutableStateFlow<List<Document>>(emptyList())
    private val _tagChangeNotifier = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    private fun emit(event: TriggerEvent, documentId: String) {
        eventBus.emit(DocumentEvent(event, documentId))
    }

    init {
        scope.launch {
            store.repairPageNumbers()
            refreshDocuments()
        }
        scope.launch { ocrManager.metadataChanged.collect { refreshDocuments() } }
    }

    private suspend fun refreshDocuments() {
        store.listDocuments().onSuccess { stored ->
            _documents.value = stored.map { it.toDomain() }
        }
    }

    override fun observeDocuments(): Flow<List<Document>> {
        scope.launch { refreshDocuments() }
        return combine(_documents.asStateFlow(), documentAccess.timestamps) { docs, access ->
            docs.map { it.copy(lastAccessedAt = access[it.id] ?: it.updatedAt) }
        }
    }

    override fun observeDocument(documentId: DocumentId): Flow<Document?> {
        return merge(
            _documents.asStateFlow().map { Unit },
            ocrManager.metadataChanged,
        ).map {
            store.readMetadata(documentId).getOrNull()?.toDomain()?.withAccess()
        }
    }

    override fun observePages(documentId: DocumentId): Flow<List<Page>> {
        return merge(
            _documents.asStateFlow().map { Unit },
            ocrManager.metadataChanged,
        ).map {
            val stored = store.readMetadata(documentId).getOrNull() ?: return@map emptyList()
            stored.pages.mapIndexed { _, sp ->
                Page(
                    id = sp.pageNumber.toLong(),
                    documentId = documentId,
                    pageNumber = sp.pageNumber,
                    filename = sp.filename,
                    imageUri = store.pageFile(documentId, sp.filename).toURI().toString(),
                    ocrText = sp.ocrText,
                    filterTypeOrdinal = sp.filterTypeOrdinal,
                    createdAt = sp.createdAt,
                    kind = sp.kind,
                    pdfPageIndex = sp.pdfPageIndex,
                )
            }
        }
    }

    override suspend fun getDocument(documentId: DocumentId): Result<Document> {
        return store.readMetadata(documentId).map { it.toDomain().withAccess() }
    }

    override suspend fun getPages(documentId: DocumentId): Result<List<Page>> {
        return store.readMetadata(documentId).map { stored ->
            stored.pages.map { sp ->
                Page(
                    id = sp.pageNumber.toLong(),
                    documentId = documentId,
                    pageNumber = sp.pageNumber,
                    filename = sp.filename,
                    imageUri = store.pageFile(documentId, sp.filename).toURI().toString(),
                    ocrText = sp.ocrText,
                    filterTypeOrdinal = sp.filterTypeOrdinal,
                    createdAt = sp.createdAt,
                    kind = sp.kind,
                    pdfPageIndex = sp.pdfPageIndex,
                )
            }
        }
    }

    override suspend fun createDocument(name: String, qualityTier: Int, pageSize: String?): Result<DocumentId> {
        return store.createDocument(name = name, qualityTier = qualityTier, pageSize = pageSize).map { stored ->
            scope.launch { refreshDocuments() }
            emit(TriggerEvent.DOC_CREATED, stored.id)
            stored.id
        }
    }

    override suspend fun addPage(
        documentId: DocumentId,
        imageUri: String,
        filterTypeOrdinal: Int,
        fileSizeBytes: Long,
        qualityTier: Int,
    ): Result<Unit> {
        return runCatching {
            val src = java.io.File(java.net.URI(imageUri))
            val tier = QualityTier.entries.getOrNull(qualityTier) ?: QualityTier.BEST
            val dir = store.documentDir(documentId)
            val tmp = java.io.File(dir, "tmp_encode_${System.nanoTime()}")
            PageEncoder.encodePage(src, tmp, tier)
            val filename = store.pageFilenameFor(tmp.readBytes())
            val pageFile = store.pageFile(documentId, filename)
            tmp.renameTo(pageFile)
            store.appendPage(
                documentId = documentId,
                filename = filename,
                fileSizeBytes = pageFile.length(),
                filterTypeOrdinal = filterTypeOrdinal,
            ).getOrThrow()
            scope.launch { refreshDocuments() }
            emit(TriggerEvent.PAGES_ADDED, documentId)
        }
    }

    override suspend fun collatePages(
        documentId: DocumentId,
        sourcePageNumbers: List<Int>,
        mergedImageUri: String,
        removeSources: Boolean,
        qualityTier: Int,
    ): Result<Unit> {
        val inheritedOcr = inheritedOcrText(documentId, sourcePageNumbers)
        return runCatching {
            val src = java.io.File(java.net.URI(mergedImageUri))
            val tier = QualityTier.entries.getOrNull(qualityTier) ?: QualityTier.BEST
            val dir = store.documentDir(documentId)
            val tmp = java.io.File(dir, "tmp_collate_${System.nanoTime()}")
            PageEncoder.encodePage(src, tmp, tier)
            val filename = store.pageFilenameFor(tmp.readBytes())
            val pageFile = store.pageFile(documentId, filename)
            tmp.renameTo(pageFile)
            store.insertMergedPage(
                documentId = documentId,
                mergedFilename = filename,
                mergedFileSize = pageFile.length(),
                sourcePageNumbers = sourcePageNumbers,
                removeSources = removeSources,
                mergedOcrText = inheritedOcr,
            ).getOrThrow()
            scope.launch { refreshDocuments() }
            emit(TriggerEvent.PAGES_ADDED, documentId)
            if (inheritedOcr == null) {
                scope.launch { ocrManager.runOcr(documentId) }
            }
        }
    }

    /**
     * Concatenates the selected pages' OCR text in page order so the merged page
     * stays searchable. Returns null when any selected page has no text yet.
     */
    private suspend fun inheritedOcrText(documentId: DocumentId, sourcePageNumbers: List<Int>): String? {
        val doc = store.readMetadata(documentId).getOrNull() ?: return null
        val texts = doc.pages
            .filter { it.pageNumber in sourcePageNumbers }
            .sortedBy { it.pageNumber }
            .map { it.ocrText }
        if (texts.isEmpty() || texts.any { it == null }) return null
        return texts.filterNotNull().joinToString("\n")
    }

    override suspend fun updatePageOcrText(documentId: DocumentId, pageNumber: Int, ocrText: String): Result<Unit> {
        return store.updatePageOcrText(documentId, pageNumber, ocrText)
    }

    override suspend fun updateDocumentName(documentId: DocumentId, name: String): Result<Unit> {
        return store.updateDocumentName(documentId, name).onSuccess {
            scope.launch { refreshDocuments() }
            emit(TriggerEvent.RENAMED, documentId)
        }
    }

    override suspend fun getDocumentsByName(name: String): Result<List<Document>> {
        return store.listDocuments().map { stored ->
            stored.filter { it.name.equals(name, ignoreCase = true) }
                .map { it.toDomain().withAccess() }
        }
    }

    override suspend fun getAllDocuments(): Result<List<Document>> {
        return store.listDocuments().map { stored -> stored.map { it.toDomain().withAccess() } }
    }

    override suspend fun deleteDocumentsByName(name: String): Result<Unit> {
        return store.listDocuments().mapCatching { stored ->
            val docs = stored.filter { it.name.equals(name, ignoreCase = true) }
            documentAccess.remove(docs.map { it.id })
            for (doc in docs) {
                store.deleteDocument(doc.id).getOrThrow()
                emit(TriggerEvent.DELETED, doc.id)
            }
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun deleteDocuments(documentIds: List<DocumentId>): Result<Unit> {
        return runCatching {
            documentAccess.remove(documentIds)
            for (id in documentIds) {
                store.deleteDocument(id).getOrThrow()
                emit(TriggerEvent.DELETED, id)
            }
            scope.launch { refreshDocuments() }
        }
    }

    override suspend fun deleteDocument(documentId: DocumentId): Result<Unit> {
        return store.deleteDocument(documentId).onSuccess {
            documentAccess.remove(listOf(documentId))
            scope.launch { refreshDocuments() }
            emit(TriggerEvent.DELETED, documentId)
        }
    }

    override suspend fun markDocumentAccessed(documentId: DocumentId) {
        documentAccess.touch(documentId)
    }

    override suspend fun deletePage(documentId: DocumentId, pageNumber: Int): Result<Unit> {
        return store.removePage(documentId, pageNumber).onSuccess {
            scope.launch { refreshDocuments() }
            emit(TriggerEvent.PAGE_REMOVED, documentId)
        }
    }

    override suspend fun replacePages(documentId: DocumentId, keptFilenames: List<String>): Result<Unit> {
        return store.replacePages(documentId, keptFilenames).mapCatching {
            scope.launch { refreshDocuments() }
            emit(TriggerEvent.PAGE_REMOVED, documentId)
        }
    }

    override suspend fun reorderPages(documentId: DocumentId, pageNumbers: List<Int>): Result<Unit> {
        return store.reorderPages(documentId, pageNumbers).onSuccess {
            scope.launch { refreshDocuments() }
            emit(TriggerEvent.PAGES_REORDERED, documentId)
        }
    }

    override suspend fun importPdf(uri: Uri): Result<DocumentId> {
        return runCatching {
            val contentResolver = app.contentResolver
            val displayName = resolvePdfDisplayName(contentResolver, uri)
            val doc = store.createDocument(name = displayName).getOrThrow()
            val targetDir = store.documentDir(doc.id)
            val tier = readDefaultQualityTier()

            val tempSource = File(targetDir, "tmp_source_${System.nanoTime()}.pdf")
            val copied = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tempSource.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            } catch (e: Exception) {
                false
            }

            val texts = if (copied) pdfStructure.pageTextsOrNull(tempSource) else null
            if (texts != null) {
                val filename = PageNaming.filenameFor(tempSource, "pdf")
                val dest = File(targetDir, filename)
                if (dest.exists()) dest.delete()
                tempSource.renameTo(dest)
                texts.forEachIndexed { index, text ->
                    store.appendPage(
                        documentId = doc.id,
                        filename = filename,
                        fileSizeBytes = dest.length(),
                        kind = PageKind.PDF,
                        pdfPageIndex = index,
                        ocrText = text.ifBlank { null },
                    ).getOrThrow()
                }
                store.refreshOcrComplete(doc.id)
            } else {
                if (tempSource.exists()) tempSource.delete()
                val results = pdfPageImporter.import(contentResolver, uri, targetDir, tier).getOrThrow()
                if (results.isEmpty()) {
                    store.deleteDocument(doc.id)
                    throw Exception("Selected PDF has no pages")
                }
                for (r in results) {
                    store.addPage(
                        documentId = doc.id,
                        pageNumber = r.pageNumber,
                        filename = r.filename,
                        fileSizeBytes = r.fileSizeBytes,
                    ).getOrThrow()
                }
            }

            scope.launch { refreshDocuments() }
            doc.id
        }
    }

    override fun notifyDocumentsChanged() {
        scope.launch {
            refreshDocuments()
            syncMissingTags()
        }
    }

    private suspend fun syncMissingTags() {
        val existingNames = tagDao.getAll().map { it.name }.toSet()
        val neededNames = store.listDocuments().getOrNull()
            ?.flatMap { it.tags }
            ?.distinct()
            ?.filter { it !in existingNames } ?: return
        var colorIndex = existingNames.size
        for (name in neededNames) {
            tagDao.insert(TagEntity(name = name, colorIndex = colorIndex % 8))
            colorIndex++
        }
    }

    override suspend fun rescanPage(documentId: DocumentId, pageNumber: Int, imageUri: String): Result<Unit> {
        return store.readMetadata(documentId).mapCatching { doc ->
            val idx = doc.pages.indexOfFirst { it.pageNumber == pageNumber }
            if (idx < 0) throw Exception("Page $pageNumber not found in document $documentId")
            val oldFilename = doc.pages[idx].filename

            val src = File(java.net.URI(imageUri))
            val tier = QualityTier.entries.getOrNull(doc.qualityTier) ?: QualityTier.BEST
            val dir = store.documentDir(documentId)
            val tmp = File(dir, "tmp_encode_${System.nanoTime()}")
            PageEncoder.encodePage(src, tmp, tier)
            val filename = store.pageFilenameFor(tmp.readBytes())
            val pageFile = store.pageFile(documentId, filename)
            tmp.renameTo(pageFile)

            store.replacePageImage(
                documentId = documentId,
                pageNumber = pageNumber,
                filename = filename,
                fileSizeBytes = pageFile.length(),
            ).getOrThrow()

            if (filename != oldFilename) {
                store.deleteFileIfUnreferenced(documentId, oldFilename)
            }

            scope.launch { refreshDocuments() }
            scope.launch { ocrManager.runOcr(documentId) }
            emit(TriggerEvent.PAGE_RESCANNED, documentId)
        }
    }

    private fun resolvePdfDisplayName(contentResolver: android.content.ContentResolver, uri: Uri): String {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val name = it.getString(nameIndex)
                    if (name != null) {
                        return name.substringBeforeLast(".")
                    }
                }
            }
        }
        val path = uri.lastPathSegment ?: "Imported Document"
        return path.substringBeforeLast(".")
    }

    private fun readDefaultQualityTier(): QualityTier {
        val prefs = app.getSharedPreferences("settings", 0)
        val ordinal = prefs.getInt("quality_tier", 0)
        return QualityTier.entries.getOrNull(ordinal) ?: QualityTier.BEST
    }

    override suspend fun searchDocumentsByOcrText(query: String): Result<Set<DocumentId>> {
        val regex = try { Regex(query, RegexOption.IGNORE_CASE) } catch (e: Exception) { return Result.success(emptySet()) }
        return store.listDocuments().map { docs ->
            docs.filter { doc ->
                doc.pages.any { page -> page.ocrText?.let { regex.containsMatchIn(it) } == true }
            }.map { it.id }.toSet()
        }
    }

    override fun observeAllTags(): Flow<List<Tag>> {
        return tagDao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }

    override fun observeDocumentTags(documentId: DocumentId): Flow<List<Tag>> {
        return merge(_documents.asStateFlow(), _tagChangeNotifier.asSharedFlow()).map {
            val stored = store.readMetadata(documentId).getOrNull() ?: return@map emptyList()
            val tagNames = stored.tags
            if (tagNames.isEmpty()) return@map emptyList()
            val allTags = tagDao.getAll()
            tagNames.mapNotNull { name ->
                allTags.find { it.name.equals(name, ignoreCase = true) }?.toDomain()
            }
        }
    }

    override fun observeDocumentTagMap(): Flow<Map<DocumentId, List<Tag>>> {
        // React to the document list, tag set/unset on documents, and changes to
        // the tag table (create/rename/delete) — documents carry tag names, and
        // `_documents` does not change when only tags change.
        return merge(
            _documents.asStateFlow().map { Unit },
            _tagChangeNotifier.asSharedFlow(),
            observeAllTags().map { Unit },
        ).map {
            val docs = _documents.value
            val allTagEntities = tagDao.getAll()
            docs.mapNotNull { doc ->
                val stored = store.readMetadata(doc.id).getOrNull() ?: return@mapNotNull null
                val tagNames = stored.tags
                if (tagNames.isEmpty()) return@mapNotNull null
                doc.id to tagNames.mapNotNull { name ->
                    allTagEntities.find { it.name.equals(name, ignoreCase = true) }?.toDomain()
                }
            }.toMap()
        }
    }

    override fun searchTags(query: String): Flow<List<Tag>> {
        return tagDao.search(query).map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun createTag(name: String): Long {
        val nextColor = (tagDao.getMaxColorIndex() + 1) % 8
        return tagDao.insert(TagEntity(name = name, colorIndex = nextColor))
    }

    override suspend fun renameTag(tagId: Long, name: String) {
        val old = tagDao.getByIds(listOf(tagId)).firstOrNull()?.name ?: return
        if (name.isBlank() || name.equals(old, ignoreCase = true)) return
        tagDao.update(TagEntity(id = tagId, name = name))
        rewriteDocumentTagName(old, name)
        _tagChangeNotifier.emit(Unit)
        scope.launch { refreshDocuments() }
    }

    /** Documents reference tags by name; a rename must follow every reference. */
    private suspend fun rewriteDocumentTagName(old: String, new: String) {
        store.listDocuments().getOrNull().orEmpty().forEach { doc ->
            if (doc.tags.none { it.equals(old, ignoreCase = true) }) return@forEach
            val tags = doc.tags
                .map { if (it.equals(old, ignoreCase = true)) new else it }
                .distinct()
            store.writeMetadata(doc.id, doc.copy(tags = tags))
        }
    }

    override suspend fun deleteTags(tagIds: List<Long>) {
        val names = tagDao.getByIds(tagIds).map { it.name }
        tagDao.deleteByIds(tagIds)
        removeDocumentTagNames(names)
        _tagChangeNotifier.emit(Unit)
        scope.launch { refreshDocuments() }
    }

    private suspend fun removeDocumentTagNames(names: List<String>) {
        if (names.isEmpty()) return
        val lowered = names.map { it.lowercase() }.toSet()
        store.listDocuments().getOrNull().orEmpty().forEach { doc ->
            if (doc.tags.none { it.lowercase() in lowered }) return@forEach
            val tags = doc.tags.filterNot { it.lowercase() in lowered }
            store.writeMetadata(doc.id, doc.copy(tags = tags))
        }
    }

    override suspend fun setDocumentTags(documentId: DocumentId, tagIds: List<Long>) {
        val tags = tagDao.getByIds(tagIds)
        val tagNames = tags.map { it.name }
        val doc = store.readMetadata(documentId).getOrNull() ?: return
        val previous = doc.tags.toSet()
        store.writeMetadata(documentId, doc.copy(tags = tagNames))
        _tagChangeNotifier.emit(Unit)
        scope.launch { refreshDocuments() }
        if (tagNames.any { it !in previous }) emit(TriggerEvent.TAGGED, documentId)
        if (previous.any { it !in tagNames }) emit(TriggerEvent.UNTAGGED, documentId)
    }

    private fun Document.withAccess(): Document =
        copy(lastAccessedAt = documentAccess.snapshot()[id] ?: updatedAt)

    private fun StoredDocument.toDomain(): Document {
        return Document(
            id = id,
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
            pageCount = pages.size,
            totalFileSize = pages.distinctBy { it.filename }.sumOf { it.fileSizeBytes },
            qualityTier = qualityTier,
            ocrComplete = ocrComplete,
            pageSize = pageSize,
        )
    }

    private fun TagEntity.toDomain(): Tag {
        return Tag(
            id = id,
            name = name,
            colorIndex = colorIndex,
        )
    }
}
