package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.picpocket.app.debug.Category
import com.picpocket.app.debug.Tracing
import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.MetadataNaming
import com.picpocket.app.data.store.StoredDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

class RegistryWriteException(message: String) : Exception(message)

class CorruptRegistryException(message: String) : Exception(message)

data class OrphanedDocument(
    val docId: String,
    val name: String,
    val deletingDeviceName: String,
    val deletedAt: Long,
    val pageCount: Int,
    var acknowledged: Boolean = false,
    val isOwnDeletion: Boolean = false,
)

@Singleton
class DeviceRegistry @Inject constructor(
    private val driveFileManager: DriveFileManager,
    private val documentStore: DocumentStore,
    private val localDriveIndex: LocalDriveIndex,
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val orphans = mutableListOf<OrphanedDocument>()
    var remoteEncrypted: Boolean = false
        private set

    fun getOrphans(): List<OrphanedDocument> = orphans.filter { !it.acknowledged }

    fun getMyDeleted(): List<OrphanedDocument> = getOrphans().filter { it.isOwnDeletion }

    fun getOthersDeleted(): List<OrphanedDocument> = getOrphans().filter { !it.isOwnDeletion }

    suspend fun detectOrphans(
        localDocs: List<StoredDocument>,
        remoteDocs: List<DownloadEngine.RemoteDocument>,
        remoteCache: Map<String, List<DocumentFile>>? = null,
    ) {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        orphans.clear()
        val devices = localDriveIndex.getDevices()

        for (remote in remoteDocs) {
            if (!remote.isDeleted) continue
            val localDoc = localDocs.find { it.id == remote.docId }
            if (localDoc == null) continue

            val byDevice = if (".deleted" in remote.fileNames) {
                val data = driveFileManager.readFile(treeUri, remote.docId, ".deleted", remoteCache)
                if (data != null) {
                    try {
                        json.decodeFromString<TombstoneData>(String(data, Charsets.UTF_8))
                    } catch (_: Exception) {
                        null
                    }
                } else null
            } else null

            val localDeviceId = localDriveIndex.getLocalDeviceId()
            val deletingDeviceName = if (byDevice != null) {
                devices[byDevice.byDevice]?.name ?: byDevice.byDevice
            } else "Unknown device"

            orphans.add(
                OrphanedDocument(
                    docId = remote.docId,
                    name = localDoc.name,
                    deletingDeviceName = deletingDeviceName,
                    deletedAt = byDevice?.deletedAt ?: 0L,
                    pageCount = localDoc.pages.size,
                    isOwnDeletion = byDevice?.byDevice == localDeviceId,
                ),
            )
        }
    }

    suspend fun keepOrphan(docId: String) {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isNotBlank()) {
            driveFileManager.deleteFileByName(treeUri, docId, ".deleted")
        }
        val doc = documentStore.readMetadata(docId).getOrNull() ?: return
        val remoteVersion = if (treeUri.isNotBlank()) {
            driveFileManager.listFileNames(treeUri, docId)
                .mapNotNull { MetadataNaming.parse(it)?.first }
                .maxOrNull() ?: 0
        } else 0
        val localVersion = documentStore.metadataVersion(docId)
        val passphrase = documentStore.metadataPassphrase(docId)
        documentStore.writeMetadataAt(docId, doc, maxOf(localVersion, remoteVersion) + 1, passphrase)
        val orphan = orphans.find { it.docId == docId }
        orphan?.acknowledged = true
    }

    suspend fun deleteOrphanLocally(docId: String) {
        documentStore.deleteDocument(docId)
        acknowledgeTombstone(docId)
        val orphan = orphans.find { it.docId == docId }
        orphan?.acknowledged = true
    }

    suspend fun dismissOrphan(docId: String) {
        acknowledgeTombstone(docId)
        val orphan = orphans.find { it.docId == docId }
        orphan?.acknowledged = true
    }

    private suspend fun acknowledgeTombstone(docId: String) {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        val data = driveFileManager.readFile(treeUri, docId, ".deleted") ?: return
        val tombstone = try {
            json.decodeFromString<TombstoneData>(String(data, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val deviceId = localDriveIndex.getLocalDeviceId()
        if (deviceId !in tombstone.acknowledgedBy) {
            val updated = tombstone.copy(acknowledgedBy = tombstone.acknowledgedBy + deviceId)
            driveFileManager.writeFile(
                treeUri, docId, ".deleted",
                json.encodeToString(updated).toByteArray(Charsets.UTF_8),
            )
        }
    }

    fun getDeviceName(): String {
        val systemName = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) { null }
        return if (!systemName.isNullOrBlank()) systemName else Build.MODEL
    }

    suspend fun syncRegistryToDrive(encrypted: Boolean = false) {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        val deviceId = localDriveIndex.getLocalDeviceId()
        if (deviceId.isBlank()) return
        localDriveIndex.setDevice(
            deviceId,
            localDriveIndex.getDevices()[deviceId]?.copy(
                name = getDeviceName(),
                lastSeen = System.currentTimeMillis(),
            ) ?: DeviceInfo(
                name = getDeviceName(),
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
            ),
        )
        val registry = SharedDeviceRegistry(
            encrypted = encrypted,
            devices = localDriveIndex.getDevices().map { (id, info) ->
                SharedDevice(id = id, name = info.name, lastSeen = info.lastSeen)
            },
        )
        val data = json.encodeToString(registry).toByteArray(Charsets.UTF_8)
        var outcome: WriteOutcome = WriteOutcome.Failed("not attempted")
        for (attempt in 1..REGISTRY_WRITE_ATTEMPTS) {
            outcome = driveFileManager.writeRootFile(treeUri, REGISTRY_FILE, data)
            if (outcome is WriteOutcome.Verified) {
                Tracing.d(Category.STORE_STATE, TAG, "syncRegistryToDrive: $REGISTRY_FILE verified after $attempt attempt(s)")
                return
            }
            val reason = (outcome as? WriteOutcome.Failed)?.reason ?: outcome.toString()
            Tracing.w(Category.STORE_STATE, TAG, "syncRegistryToDrive: attempt $attempt failed to verify $REGISTRY_FILE ($reason)")
            if (attempt < REGISTRY_WRITE_ATTEMPTS) delay(REGISTRY_WRITE_RETRY_DELAY_MS)
        }
        throw RegistryWriteException(
            "Failed to write $REGISTRY_FILE to drive after $REGISTRY_WRITE_ATTEMPTS attempts: " +
                ((outcome as? WriteOutcome.Failed)?.reason ?: outcome.toString()),
        )
    }

    suspend fun syncRegistryFromDrive() {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null) { Tracing.w(Category.STORE_STATE, TAG, "syncRegistryFromDrive: root null"); return }

        // The Nextcloud bridge serves file contents from a cache that lags
        // the server by one refresh cycle, so a just-listed devices.json can
        // transiently read back empty. Retry before treating it as corrupt;
        // only a persistent decode failure halts the sync.
        for (attempt in 1..REGISTRY_READ_ATTEMPTS) {
            try { context.contentResolver.refresh(root.uri, null, null) } catch (_: Throwable) { }
            val file = root.listFiles().find { it.name == REGISTRY_FILE }
            if (file == null) { Tracing.w(Category.STORE_STATE, TAG, "syncRegistryFromDrive: $REGISTRY_FILE not found"); return }
            // Refresh the file node too: the bridge serves document contents
            // from a cache that lags the server by a refresh cycle, so the root
            // refresh alone can still yield an empty read for this file.
            try { context.contentResolver.refresh(file.uri, null, null) } catch (_: Throwable) { }
            val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                Tracing.w(Category.STORE_STATE, TAG, "syncRegistryFromDrive: attempt $attempt read ${bytes?.size ?: 0} bytes, retrying")
                if (attempt < REGISTRY_READ_ATTEMPTS) { delay(REGISTRY_READ_RETRY_DELAY_MS); continue }
                break
            }
            val remote = try {
                json.decodeFromString<SharedDeviceRegistry>(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                Tracing.w(Category.STORE_STATE, TAG, "syncRegistryFromDrive: attempt $attempt decode failed (${e.message})")
                if (attempt < REGISTRY_READ_ATTEMPTS) { delay(REGISTRY_READ_RETRY_DELAY_MS); continue }
                throw CorruptRegistryException("$REGISTRY_FILE on drive is corrupt (${e.message}); sync halted")
            }
            remoteEncrypted = remote.encrypted
            val localDeviceId = localDriveIndex.getLocalDeviceId()
            for (device in remote.devices) {
                if (device.id == localDeviceId) continue
                val existing = localDriveIndex.getDevices()[device.id]
                if (existing == null) {
                    localDriveIndex.setDevice(
                        device.id,
                        DeviceInfo(name = device.name, firstSeen = device.lastSeen, lastSeen = device.lastSeen),
                    )
                }
            }
            Tracing.d(Category.STORE_STATE, TAG, "syncRegistryFromDrive: imported ${remote.devices.size} device(s)")
            return
        }
        throw CorruptRegistryException("$REGISTRY_FILE exists on drive but could not be read; sync halted")
    }

    suspend fun cleanDrive() {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        val allDeviceIds = localDriveIndex.getAllKnownDeviceIds()
        if (allDeviceIds.isEmpty()) return

        val docIds = driveFileManager.listDocFolders(treeUri)

        for (docId in docIds) {
            val fileNames = driveFileManager.listFileNames(treeUri, docId)
            if (".deleted" !in fileNames) continue
            val data = driveFileManager.readFile(treeUri, docId, ".deleted") ?: continue
            val tombstone = try {
                json.decodeFromString<TombstoneData>(String(data, Charsets.UTF_8))
            } catch (_: Exception) {
                continue
            }

            if (allDeviceIds.all { it in tombstone.acknowledgedBy }) {
                driveFileManager.deleteFileByName(treeUri, docId, ".deleted")
                val remaining = driveFileManager.listFileNames(treeUri, docId)
                if (remaining.isEmpty()) {
                    val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    root?.findFile(docId)?.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "DeviceRegistry"
        private const val REGISTRY_FILE = "devices.json"
        private const val REGISTRY_WRITE_ATTEMPTS = 3
        private const val REGISTRY_WRITE_RETRY_DELAY_MS = 2_000L
        private const val REGISTRY_READ_ATTEMPTS = 5
        private const val REGISTRY_READ_RETRY_DELAY_MS = 3_000L
    }
}

@Serializable
data class SharedDevice(
    val id: String,
    val name: String,
    val lastSeen: Long,
)

@Serializable
data class SharedDeviceRegistry(
    val devices: List<SharedDevice>,
    val encrypted: Boolean = false,
)
