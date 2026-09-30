package com.picpocket.app.drive.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import com.picpocket.app.debug.Category
import com.picpocket.app.debug.Tracing
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.MetadataNaming
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.DriveConnectivityChecker
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.drive.PassphraseStore
import com.picpocket.app.drive.SyncState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val driveAuthManager: DriveAuthManager,
    private val documentRepository: DocumentRepository,
    private val documentStore: DocumentStore,
    private val uploadEngine: UploadEngine,
    private val downloadEngine: DownloadEngine,
    private val localDriveIndex: LocalDriveIndex,
    private val driveConnectivityChecker: DriveConnectivityChecker,
    private val defaultSyncScheduler: DefaultSyncScheduler,
    private val driveFileManager: DriveFileManager,
    private val deviceRegistry: DeviceRegistry,
    private val encryptionManager: EncryptionManager,
    private val passphraseStore: PassphraseStore,
    private val retryHandler: RetryHandler,
    private val syncSettings: SyncSettings,
    private val syncMutex: SyncMutex,
    @ApplicationContext private val context: Context,
) {
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var isSyncing = false

    init {
        defaultSyncScheduler.setSyncCallback { performSync() }
        ensureDeviceRegistered()
    }

    private fun getDeviceName(): String {
        val systemName = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) { null }
        return if (!systemName.isNullOrBlank()) systemName else Build.MODEL
    }

    private fun ensureDeviceRegistered() {
        if (localDriveIndex.getLocalDeviceId().isBlank()) {
            val deviceId = java.util.UUID.randomUUID().toString()
            localDriveIndex.setLocalDeviceId(deviceId)
            localDriveIndex.setDevice(
                deviceId,
                DeviceInfo(
                    name = getDeviceName(),
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun performSync() {
        if (!syncSettings.syncEnabled) { Tracing.d(Category.DRIVE_API, TAG, "performSync: sync disabled"); return }
        val authState = driveAuthManager.authState.value
        if (authState !is DriveAuthState.Connected) { Tracing.d(Category.DRIVE_API, TAG, "performSync: not connected (${authState::class.simpleName})"); return }
        if (!driveConnectivityChecker.isNetworkAvailable()) { Tracing.d(Category.DRIVE_API, TAG, "performSync: no network"); return }
        if (isSyncing) { Tracing.d(Category.DRIVE_API, TAG, "performSync: already syncing"); return }
        if (!localDriveIndex.hasValidFolder()) { Tracing.d(Category.DRIVE_API, TAG, "performSync: no folder configured"); _syncState.value = SyncState.Error("No folder configured"); return }

        // Cache the persisted passphrase: a sync (e.g. from the background worker)
        // can run before any screen loads it, and encryption plus the verifier
        // depend on the cached value.
        if (!encryptionManager.isEncryptionEnabled) {
            passphraseStore.getPassphrase()?.takeIf { it.isNotBlank() }?.let { encryptionManager.setPassphrase(it) }
        }

        Tracing.d(Category.DRIVE_API, TAG, "performSync: starting rootFolderId='${localDriveIndex.getRootFolderId()}'")
        isSyncing = true
        _syncState.value = SyncState.Syncing
        try {
            syncMutex.initialize()
            if (!syncMutex.acquire()) {
                Tracing.d(Category.DRIVE_API, TAG, "performSync: mutex locked by another device")
                _syncState.value = SyncState.Idle
                return
            }
            var uploadFailure: String? = null
            coroutineScope {
                // Renew the lock lease for the whole sync: a sync longer than
                // the stale timeout would otherwise look crashed and be taken
                // over mid-upload. This only rewrites our own token file; it
                // never aborts or interferes with the sync.
                val heartbeatJob = launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        syncMutex.heartbeat()
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                retryHandler.waitBeforeRetry()
                Tracing.d(Category.DRIVE_API, TAG, "performSync: waitBeforeRetry done")

                val localDocs = documentStore.listDocuments().getOrDefault(emptyList())
                Tracing.d(Category.STORE_STATE, TAG, "performSync: localDocs count=${localDocs.size}")

                refreshSafCache()

                Tracing.d(Category.DRIVE_FILES, TAG, "performSync: prefetching remote file tree...")
                val remoteCache = driveFileManager.prefetchRemoteFiles(
                    localDriveIndex.getRootTreeUri()
                )

                Tracing.d(Category.DRIVE_FILES, TAG, "performSync: listing remote docs...")
                val remoteDocs = downloadEngine.listRemoteDocuments(remoteCache)

                deviceRegistry.syncRegistryFromDrive()

                val remoteEncrypted = deviceRegistry.remoteEncrypted
                val passphraseSet = encryptionManager.isEncryptionEnabled
                val remotePassphraseCount = remoteDocs.maxOfOrNull { it.passphrase } ?: 0
                val localPassphraseCount = localDriveIndex.passphraseCount

                Tracing.d(Category.DRIVE_API, TAG, "performSync: gating remoteEncrypted=$remoteEncrypted passphraseSet=$passphraseSet localCount=$localPassphraseCount remoteCount=$remotePassphraseCount remoteDocsSize=${remoteDocs.size}")

                if (remoteEncrypted && !passphraseSet) {
                    Tracing.w(Category.DRIVE_API, TAG, "performSync: Drive encrypted, passphrase required")
                    _syncState.value = SyncState.Error("This Drive is encrypted. Enter your passphrase to sync.")
                    throw SyncAborted()
                }

                if (passphraseSet && localPassphraseCount < remotePassphraseCount) {
                    Tracing.w(Category.DRIVE_API, TAG, "performSync: stale passphrase generation (local $localPassphraseCount < remote $remotePassphraseCount)")
                    _syncState.value = SyncState.Error("Wrong passphrase. Sync disabled until corrected.")
                    throw SyncAborted()
                }

                if (localPassphraseCount < remotePassphraseCount) {
                    localDriveIndex.passphraseCount = remotePassphraseCount
                }

                val excludeIds = localDocs.filter { it.syncExclude || remoteDocs.any { r -> r.docId == it.id && r.isDeleted } }.map { it.id }.toSet()

                reconcile(localDocs, remoteDocs, excludeIds, remoteCache, { reason ->
                    uploadFailure = reason
                })

                documentRepository.notifyDocumentsChanged()

                deviceRegistry.detectOrphans(localDocs, remoteDocs, remoteCache)

                deviceRegistry.syncRegistryToDrive(passphraseSet)

                retryHandler.onSuccess()
                }
                } finally {
                    heartbeatJob.cancel()
                }
                val failure = uploadFailure
                if (failure != null) {
                    _syncState.value = SyncState.Error(failure)
                    return@coroutineScope
                }
                _syncState.value = SyncState.Idle
                Tracing.d(Category.DRIVE_API, TAG, "performSync: complete")
            }
        } catch (_: SyncAborted) {
            // state already set by gating code, fall through
        } catch (e: Exception) {
            retryHandler.onFailure()
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
            Tracing.d(Category.DRIVE_API, TAG, "performSync: error ${e.message}")
        } finally {
            syncMutex.release()
            isSyncing = false
        }
    }

    private suspend fun reconcile(
        localDocs: List<com.picpocket.app.data.store.StoredDocument>,
        remoteDocs: List<DownloadEngine.RemoteDocument>,
        excludeIds: Set<String>,
        remoteCache: Map<String, List<androidx.documentfile.provider.DocumentFile>>?,
        onFailure: (String) -> Unit,
    ) {
        val currentCount = localDriveIndex.passphraseCount

        for (local in localDocs) {
            if (local.id in excludeIds) {
                Tracing.d(Category.DRIVE_FILES, TAG, "reconcile: doc=${local.id} excluded")
                continue
            }
            val remote = remoteDocs.find { it.docId == local.id }
            val localVersion = documentStore.metadataVersion(local.id)
            val localCount = documentStore.metadataPassphrase(local.id)

            val action = when {
                remote == null -> "uploadNew"
                localVersion > remote.version -> "push"
                remote.version > localVersion -> "pull"
                currentCount > remote.passphrase -> "reEncrypt"
                else -> "noop"
            }
            Tracing.d(Category.DRIVE_FILES, TAG, "reconcile: doc=${local.id} localV=$localVersion remoteV=${remote?.version} localC=$localCount remoteC=${remote?.passphrase} -> $action")

            val ok = when (action) {
                "uploadNew" -> try {
                    uploadEngine.uploadNewDocument(local.id)
                } catch (e: Exception) {
                    Tracing.e(Category.DRIVE_FILES, TAG, "reconcile: uploadNew threw for doc=${local.id}: ${e.message}")
                    false
                }
                "push" -> try {
                    uploadEngine.pushDocument(local.id, remote!!.passphrase)
                } catch (e: Exception) {
                    Tracing.e(Category.DRIVE_FILES, TAG, "reconcile: push threw for doc=${local.id}: ${e.message}")
                    false
                }
                "reEncrypt" -> try {
                    uploadEngine.forceReEncryptDocument(local.id)
                } catch (e: Exception) {
                    Tracing.e(Category.DRIVE_FILES, TAG, "reconcile: reEncrypt threw for doc=${local.id}: ${e.message}")
                    false
                }
                "pull" -> try {
                    downloadEngine.pullDocument(remote!!, remoteCache)
                } catch (e: Exception) {
                    Tracing.e(Category.DRIVE_FILES, TAG, "reconcile: pull threw for doc=${local.id}: ${e.message}")
                    false
                }
                else -> true
            }
            if (!ok) {
                Tracing.e(Category.DRIVE_FILES, TAG, "reconcile: $action failed for doc=${local.id}, aborting sync")
                retryHandler.onFailure()
                onFailure("$action failed for doc=${local.id}")
                return
            }
        }

        for (remote in remoteDocs) {
            if (remote.isDeleted) continue
            if (remote.docId in excludeIds) continue
            val localExists = localDocs.any { it.id == remote.docId }
            if (localExists) continue
            if (remote.metadata == null) {
                Tracing.w(Category.DRIVE_FILES, TAG, "reconcile: remote-only doc=${remote.docId} has undecodable metadata, skipping")
                continue
            }
            Tracing.d(Category.DRIVE_FILES, TAG, "reconcile: remote-only doc=${remote.docId} v${remote.version} downloading")
            val ok = try {
                downloadEngine.pullDocument(remote, remoteCache)
            } catch (e: Exception) {
                Tracing.e(Category.DRIVE_FILES, TAG, "reconcile: download threw for doc=${remote.docId}: ${e.message}")
                false
            }
            if (!ok) {
                Tracing.e(Category.DRIVE_FILES, TAG, "reconcile: download failed for doc=${remote.docId}, aborting sync")
                retryHandler.onFailure()
                onFailure("Download failed for doc=${remote.docId}")
                return
            }
        }
    }

    private suspend fun refreshSafCache() {
        val treeUri = localDriveIndex.getRootTreeUri()
        if (treeUri.isBlank()) return
        try {
            val resolver = context.contentResolver ?: return
            val uri = Uri.parse(treeUri)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            resolver.refresh(docUri, null, null)
        } catch (e: Throwable) {
            Tracing.w(Category.DRIVE_FILES, TAG, "refreshSafCache: failed ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}

private class SyncAborted : Exception()

// Renewal cadence for the mutex lease during a long sync (matches the token's
// stale-timeout budget in SyncMutex: 30s beats 300s by a wide margin).
private const val HEARTBEAT_INTERVAL_MS = 30_000L
