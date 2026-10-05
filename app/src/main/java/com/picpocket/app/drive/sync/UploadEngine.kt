package com.picpocket.app.drive.sync

import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.MetadataNaming
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.debug.Category
import com.picpocket.app.debug.Tracing

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadEngine @Inject constructor(
    private val driveFileManager: DriveFileManager,
    private val documentStore: DocumentStore,
    private val localDriveIndex: LocalDriveIndex,
) {
    private val json = Json { prettyPrint = true }

    private fun treeUri(): String? {
        val uri = localDriveIndex.getRootTreeUri()
        return uri.ifBlank { null }
    }

    private suspend fun cleanupOldMetadata(tree: String, docId: String, newName: String) {
        for (name in driveFileManager.listFileNames(tree, docId)) {
            if (name != newName && MetadataNaming.isMetadata(name)) {
                driveFileManager.deleteFileByName(tree, docId, name)
            }
        }
    }

    suspend fun ensureFolder(docId: String): Boolean {
        val tree = treeUri() ?: return false
        return driveFileManager.createDocFolder(tree, docId)
    }

    suspend fun uploadNewDocument(docId: String): Boolean {
        val tree = treeUri() ?: return false
        val doc = documentStore.readMetadata(docId).getOrNull() ?: return false
        if (!ensureFolder(docId)) return false
        val version = documentStore.metadataVersion(docId)
        val passphrase = localDriveIndex.passphraseCount

        for (page in doc.pages.distinctBy { it.filename }) {
            val pageFile = documentStore.pageFile(docId, page.filename)
            if (pageFile.exists()) {
                val outcome = driveFileManager.writeFile(tree, docId, page.filename, pageFile.readBytes())
                if (outcome !is WriteOutcome.Verified) {
                    Tracing.w(Category.DRIVE_FILES, TAG, "uploadNewDocument: page ${page.filename} failed: ${(outcome as? WriteOutcome.Failed)?.reason}")
                    return false
                }
            }
        }

        val metadataBytes = json.encodeToString(doc).toByteArray(Charsets.UTF_8)
        val metaName = MetadataNaming.name(version, passphrase)
        val metaOutcome = driveFileManager.writeFile(tree, docId, metaName, metadataBytes)
        if (metaOutcome !is WriteOutcome.Verified) {
            Tracing.w(Category.DRIVE_FILES, TAG, "uploadNewDocument: metadata failed for $docId: ${(metaOutcome as? WriteOutcome.Failed)?.reason}")
            return false
        }

        cleanupOldMetadata(tree, docId, metaName)
        documentStore.writeMetadataAt(docId, doc, version, passphrase)
        return true
    }

    suspend fun pushDocument(docId: String, remotePassphrase: Int): Boolean {
        val tree = treeUri() ?: return false
        val doc = documentStore.readMetadata(docId).getOrNull() ?: return false
        if (!ensureFolder(docId)) return false
        val version = documentStore.metadataVersion(docId)

        val remoteNames = driveFileManager.listFileNames(tree, docId).toSet()
        val localNames = doc.pages.map { it.filename }.toSet()

        for (filename in localNames - remoteNames) {
            val pageFile = documentStore.pageFile(docId, filename)
            if (pageFile.exists()) {
                val outcome = driveFileManager.writeFile(tree, docId, filename, pageFile.readBytes())
                if (outcome !is WriteOutcome.Verified) {
                    Tracing.w(Category.DRIVE_FILES, TAG, "pushDocument: page $filename failed: ${(outcome as? WriteOutcome.Failed)?.reason}")
                    return false
                }
            }
        }

        for (name in remoteNames - localNames) {
            if (MetadataNaming.isMetadata(name) || name == ".deleted") continue
            driveFileManager.deleteFileByName(tree, docId, name)
        }

        val metadataBytes = json.encodeToString(doc).toByteArray(Charsets.UTF_8)
        val metaName = MetadataNaming.name(version, remotePassphrase)
        val metaOutcome = driveFileManager.writeFile(tree, docId, metaName, metadataBytes)
        if (metaOutcome !is WriteOutcome.Verified) {
            Tracing.w(Category.DRIVE_FILES, TAG, "pushDocument: metadata failed for $docId: ${(metaOutcome as? WriteOutcome.Failed)?.reason}")
            return false
        }

        cleanupOldMetadata(tree, docId, metaName)
        documentStore.writeMetadataAt(docId, doc, version, remotePassphrase)
        return true
    }

    suspend fun forceReEncryptDocument(docId: String): Boolean {
        val tree = treeUri() ?: return false
        val doc = documentStore.readMetadata(docId).getOrNull() ?: return false
        if (!ensureFolder(docId)) return false
        val version = documentStore.metadataVersion(docId)
        val passphrase = localDriveIndex.passphraseCount

        for (page in doc.pages) {
            val pageFile = documentStore.pageFile(docId, page.filename)
            if (pageFile.exists()) {
                val outcome = driveFileManager.writeFile(tree, docId, page.filename, pageFile.readBytes())
                if (outcome !is WriteOutcome.Verified) {
                    Tracing.w(Category.DRIVE_FILES, TAG, "forceReEncryptDocument: page ${page.filename} failed: ${(outcome as? WriteOutcome.Failed)?.reason}")
                    return false
                }
            }
        }

        val metadataBytes = json.encodeToString(doc).toByteArray(Charsets.UTF_8)
        val metaName = MetadataNaming.name(version, passphrase)
        val metaOutcome = driveFileManager.writeFile(tree, docId, metaName, metadataBytes)
        if (metaOutcome !is WriteOutcome.Verified) {
            Tracing.w(Category.DRIVE_FILES, TAG, "forceReEncryptDocument: metadata failed for $docId: ${(metaOutcome as? WriteOutcome.Failed)?.reason}")
            return false
        }

        cleanupOldMetadata(tree, docId, metaName)
        documentStore.writeMetadataAt(docId, doc, version, passphrase)
        return true
    }

    suspend fun uploadDeletedTombstone(docId: String, deviceId: String) {
        val tree = treeUri() ?: return

        val tombstoneData = json.encodeToString(
            TombstoneData(deletedAt = System.currentTimeMillis(), byDevice = deviceId, acknowledgedBy = listOf(deviceId))
        ).toByteArray(Charsets.UTF_8)

        driveFileManager.writeFile(tree, docId, ".deleted", tombstoneData)
    }

    companion object {
        private const val TAG = "UploadEngine"
    }
}

@Serializable
data class TombstoneData(
    val deletedAt: Long,
    val byDevice: String,
    val acknowledgedBy: List<String>,
)
