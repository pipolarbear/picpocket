package com.picpocket.app.data.repository

import android.net.Uri
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.Tag
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeDocuments(): Flow<List<Document>>
    fun observeDocument(documentId: DocumentId): Flow<Document?>
    fun observePages(documentId: DocumentId): Flow<List<Page>>
    suspend fun getDocument(documentId: DocumentId): Result<Document>
    suspend fun getPages(documentId: DocumentId): Result<List<Page>>
    suspend fun createDocument(name: String, qualityTier: Int = 0, pageSize: String? = null): Result<DocumentId>
    suspend fun addPage(documentId: DocumentId, imageUri: String, filterTypeOrdinal: Int = 0, fileSizeBytes: Long = 0, qualityTier: Int = 0): Result<Unit>
    suspend fun collatePages(documentId: DocumentId, sourcePageNumbers: List<Int>, mergedImageUri: String, removeSources: Boolean, qualityTier: Int = 0): Result<Unit>
    suspend fun updatePageOcrText(documentId: DocumentId, pageNumber: Int, ocrText: String): Result<Unit>
    suspend fun updateDocumentName(documentId: DocumentId, name: String): Result<Unit>
    suspend fun getDocumentsByName(name: String): Result<List<Document>>
    suspend fun getAllDocuments(): Result<List<Document>>
    suspend fun deleteDocumentsByName(name: String): Result<Unit>
    suspend fun deleteDocuments(documentIds: List<DocumentId>): Result<Unit>
    suspend fun deleteDocument(documentId: DocumentId): Result<Unit>
    suspend fun deletePage(documentId: DocumentId, pageNumber: Int): Result<Unit>
    suspend fun replacePages(documentId: DocumentId, keptFilenames: List<String>): Result<Unit>
    suspend fun reorderPages(documentId: DocumentId, pageNumbers: List<Int>): Result<Unit>
    suspend fun searchDocumentsByOcrText(query: String): Result<Set<DocumentId>>
    suspend fun importPdf(uri: Uri): Result<DocumentId>
    suspend fun rescanPage(documentId: DocumentId, pageNumber: Int, imageUri: String): Result<Unit>
    fun notifyDocumentsChanged()

    fun observeAllTags(): Flow<List<Tag>>
    fun observeDocumentTags(documentId: DocumentId): Flow<List<Tag>>
    fun observeDocumentTagMap(): Flow<Map<DocumentId, List<Tag>>>
    fun searchTags(query: String): Flow<List<Tag>>
    suspend fun createTag(name: String): Long
    suspend fun renameTag(tagId: Long, name: String)
    suspend fun deleteTags(tagIds: List<Long>)
    suspend fun setDocumentTags(documentId: DocumentId, tagIds: List<Long>)

    /** Records that the document was opened ("last seen"); local-only. */
    suspend fun markDocumentAccessed(documentId: DocumentId)
}
