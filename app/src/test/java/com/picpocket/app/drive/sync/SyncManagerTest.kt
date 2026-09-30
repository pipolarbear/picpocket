package com.picpocket.app.drive.sync

import android.content.Context
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.store.DocumentStore
import com.picpocket.app.data.store.StoredDocument
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.DriveConnectivityChecker
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.drive.PassphraseStore
import com.picpocket.app.drive.SyncState
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class SyncManagerTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveAuthManager = mockk<DriveAuthManager>()
    private val documentRepository = mockk<DocumentRepository>()
    private val documentStore = mockk<DocumentStore>()
    private val uploadEngine = mockk<UploadEngine>()
    private val downloadEngine = mockk<DownloadEngine>()
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val driveConnectivityChecker = mockk<DriveConnectivityChecker>()
    private val defaultSyncScheduler = mockk<DefaultSyncScheduler>()
    private val deviceRegistry = mockk<DeviceRegistry>()
    private val driveFileManager = mockk<DriveFileManager>()
    private val encryptionManager = mockk<EncryptionManager>()
    private val passphraseStore = mockk<PassphraseStore>(relaxed = true)
    private val retryHandler = mockk<RetryHandler>()
    private val syncSettings = mockk<SyncSettings>()
    private val context = mockk<Context>()
    private val syncMutex = mockk<SyncMutex>()

    private lateinit var syncManager: SyncManager

    @Before
    fun setUp() {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Connected)
        every { driveConnectivityChecker.isNetworkAvailable() } returns true
        every { syncSettings.syncEnabled } returns true
        every { localDriveIndex.getLocalDeviceId() } returns "device-1"
        every { localDriveIndex.getRootFolderId() } returns ""
        every { localDriveIndex.getRootTreeUri() } returns ""
        every { localDriveIndex.hasValidFolder() } returns true
        every { localDriveIndex.passphraseCount } returns 0
        every { localDriveIndex.passphraseCount = any() } returns Unit
        every { defaultSyncScheduler.setSyncCallback(any()) } returns Unit
        coEvery { retryHandler.waitBeforeRetry() } returns Unit
        coEvery { retryHandler.onSuccess() } returns Unit
        coEvery { retryHandler.onFailure() } returns Unit
        coEvery { deviceRegistry.detectOrphans(any(), any(), any()) } returns Unit
        every { documentRepository.notifyDocumentsChanged() } returns Unit
        coEvery { deviceRegistry.syncRegistryFromDrive() } returns Unit
        every { deviceRegistry.remoteEncrypted } returns false
        every { encryptionManager.isEncryptionEnabled } returns false
        coEvery { deviceRegistry.syncRegistryToDrive(any<Boolean>()) } returns Unit
        coEvery { driveFileManager.prefetchRemoteFiles(any()) } returns emptyMap()
        coEvery { syncMutex.initialize() } returns Unit
        coEvery { syncMutex.acquire() } returns true
        coEvery { syncMutex.release() } returns Unit
        coEvery { syncMutex.heartbeat() } returns Unit
        coEvery { documentStore.metadataVersion(any()) } returns 0
        coEvery { documentStore.metadataPassphrase(any()) } returns 0

        syncManager = SyncManager(
            driveAuthManager,
            documentRepository,
            documentStore,
            uploadEngine,
            downloadEngine,
            localDriveIndex,
            driveConnectivityChecker,
            defaultSyncScheduler,
            driveFileManager,
            deviceRegistry,
            encryptionManager,
            passphraseStore,
            retryHandler,
            syncSettings,
            syncMutex,
            context,
        )
    }

    private fun doc(id: String, name: String = "Test", syncExclude: Boolean = false) =
        StoredDocument(id = id, name = name, createdAt = 0L, updatedAt = 0L, syncExclude = syncExclude)

    private fun remote(
        docId: String,
        fileNames: List<String> = listOf("abc123.jpg"),
        metadata: StoredDocument? = null,
        version: Int = 0,
        passphrase: Int = 0,
        isDeleted: Boolean = false,
    ) = DownloadEngine.RemoteDocument(
        docId = docId,
        fileNames = fileNames,
        metadata = metadata,
        version = version,
        passphrase = passphrase,
        isDeleted = isDeleted,
    )

    @Test
    fun `sync returns idle when not connected`() = runTest {
        every { driveAuthManager.authState } returns MutableStateFlow(DriveAuthState.Disconnected)
        syncManager.performSync()
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync returns idle when network unavailable`() = runTest {
        every { driveConnectivityChecker.isNetworkAvailable() } returns false
        syncManager.performSync()
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync returns idle when sync disabled`() = runTest {
        every { syncSettings.syncEnabled } returns false
        syncManager.performSync()
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync uploads local-only docs via uploadNewDocument`() = runTest {
        val d = doc("doc-1")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns emptyList()
        coEvery { uploadEngine.uploadNewDocument("doc-1") } returns true

        syncManager.performSync()

        coVerify { uploadEngine.uploadNewDocument("doc-1") }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync does not upload excluded docs`() = runTest {
        val d = doc("doc-1", syncExclude = true)
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns emptyList()

        syncManager.performSync()

        coVerify(inverse = true) { uploadEngine.uploadNewDocument("doc-1") }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync pushes when local version is higher than remote`() = runTest {
        val d = doc("doc-1")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        coEvery { documentStore.metadataVersion("doc-1") } returns 5
        val r = remote("doc-1", version = 3, metadata = d)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(r)
        coEvery { uploadEngine.pushDocument("doc-1", 0) } returns true

        syncManager.performSync()

        coVerify { uploadEngine.pushDocument("doc-1", 0) }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync pulls when remote version is higher than local`() = runTest {
        val d = doc("doc-1")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        coEvery { documentStore.metadataVersion("doc-1") } returns 2
        val r = remote("doc-1", version = 4, metadata = d)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(r)
        coEvery { downloadEngine.pullDocument(any(), any()) } returns true

        syncManager.performSync()

        coVerify { downloadEngine.pullDocument(any(), any()) }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync re-encrypts when local passphrase count exceeds remote`() = runTest {
        every { localDriveIndex.passphraseCount } returns 2
        val d = doc("doc-1")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        coEvery { documentStore.metadataVersion("doc-1") } returns 5
        val r = remote("doc-1", version = 5, passphrase = 0, metadata = d)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(r)
        coEvery { uploadEngine.forceReEncryptDocument("doc-1") } returns true

        syncManager.performSync()

        coVerify { uploadEngine.forceReEncryptDocument("doc-1") }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync no-ops when versions and passphrase counts match`() = runTest {
        val d = doc("doc-1")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        coEvery { documentStore.metadataVersion("doc-1") } returns 5
        val r = remote("doc-1", version = 5, passphrase = 0, metadata = d)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(r)

        syncManager.performSync()

        coVerify(inverse = true) { uploadEngine.uploadNewDocument("doc-1") }
        coVerify(inverse = true) { uploadEngine.pushDocument(any(), any()) }
        coVerify(inverse = true) { uploadEngine.forceReEncryptDocument("doc-1") }
        coVerify(inverse = true) { downloadEngine.pullDocument(any(), any()) }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync downloads remote-only docs`() = runTest {
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        val meta = StoredDocument(id = "remote-1", name = "Remote", createdAt = 0L, updatedAt = 0L)
        val r = remote("remote-1", version = 3, metadata = meta)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(r)
        coEvery { downloadEngine.pullDocument(any(), any()) } returns true

        syncManager.performSync()

        coVerify { downloadEngine.pullDocument(any(), any()) }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync skips remote-only docs with undecodable metadata`() = runTest {
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        val r = remote("remote-1", version = 3, metadata = null)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(r)

        syncManager.performSync()

        coVerify(inverse = true) { downloadEngine.pullDocument(any(), any()) }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync excludes docs deleted remotely`() = runTest {
        val d = doc("doc-1")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        val r = remote("doc-1", isDeleted = true, metadata = null)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(r)

        syncManager.performSync()

        coVerify(inverse = true) { uploadEngine.uploadNewDocument("doc-1") }
        coVerify(inverse = true) { uploadEngine.pushDocument(any(), any()) }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync handles exception gracefully`() = runTest {
        coEvery { documentStore.listDocuments() } throws RuntimeException("Network error")
        syncManager.performSync()
        assertTrue(syncManager.syncState.value is SyncState.Error)
    }

    @Test
    fun `sync short-circuits when mutex locked by another device`() = runTest {
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(doc("doc-1")))
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns emptyList()
        coEvery { syncMutex.acquire() } returns false

        syncManager.performSync()

        assertEquals(SyncState.Idle, syncManager.syncState.value)
        coVerify { syncMutex.release() }
    }

    @Test
    fun `sync reports error when no folder configured`() = runTest {
        every { localDriveIndex.hasValidFolder() } returns false
        syncManager.performSync()
        assertEquals(SyncState.Error("No folder configured"), syncManager.syncState.value)
    }

    @Test
    fun `sync aborts with error when uploadNewDocument fails`() = runTest {
        val d = doc("doc-1")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns emptyList()
        coEvery { uploadEngine.uploadNewDocument("doc-1") } returns false

        syncManager.performSync()

        coVerify { retryHandler.onFailure() }
        assertTrue(syncManager.syncState.value is SyncState.Error)
    }

    @Test
    fun `sync stops uploading remaining docs after first failure`() = runTest {
        val d1 = doc("doc-1")
        val d2 = doc("doc-2")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d1, d2))
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns emptyList()
        coEvery { uploadEngine.uploadNewDocument("doc-1") } returns false

        syncManager.performSync()

        coVerify(inverse = true) { uploadEngine.uploadNewDocument("doc-2") }
        assertTrue(syncManager.syncState.value is SyncState.Error)
    }

    @Test
    fun `sync rerun after an interrupted upload completes the doc`() = runTest {
        // First sync: the upload is killed mid-transfer (the app is force-
        // stopped, the radio drops), leaving the sync in an error state with
        // the doc still local-only. The next sync must retry the same doc and
        // finish it — the interrupted transfer must not wedge the sync.
        val d = doc("doc-1")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns emptyList()
        var attempts = 0
        coEvery { uploadEngine.uploadNewDocument("doc-1") } answers {
            attempts++
            if (attempts == 1) throw IOException("upload interrupted mid-transfer")
            true
        }

        syncManager.performSync()
        assertTrue(syncManager.syncState.value is SyncState.Error)

        syncManager.performSync()

        assertEquals(SyncState.Idle, syncManager.syncState.value)
        coVerify(exactly = 2) { uploadEngine.uploadNewDocument("doc-1") }
    }

    @Test
    fun `gating blocks sync when remote encrypted and no passphrase set`() = runTest {
        every { deviceRegistry.remoteEncrypted } returns true
        every { encryptionManager.isEncryptionEnabled } returns false
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns emptyList()

        syncManager.performSync()

        val state = syncManager.syncState.value
        assertTrue(state is SyncState.Error)
        assertEquals("This Drive is encrypted. Enter your passphrase to sync.", (state as SyncState.Error).message)
    }

    @Test
    fun `gating allows sync when remote encrypted and passphrase count is current`() = runTest {
        every { deviceRegistry.remoteEncrypted } returns true
        every { encryptionManager.isEncryptionEnabled } returns true
        every { localDriveIndex.passphraseCount } returns 1
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        val meta = StoredDocument(id = "remote-1", name = "Doc", createdAt = 0L, updatedAt = 0L)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(remote("remote-1", version = 1, passphrase = 1, metadata = meta))
        coEvery { downloadEngine.pullDocument(any(), any()) } returns true

        syncManager.performSync()

        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `gating blocks sync when local passphrase count lags remote`() = runTest {
        every { deviceRegistry.remoteEncrypted } returns true
        every { encryptionManager.isEncryptionEnabled } returns true
        every { localDriveIndex.passphraseCount } returns 0
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(remote("remote-1", version = 1, passphrase = 2, metadata = null))

        syncManager.performSync()

        val state = syncManager.syncState.value
        assertTrue(state is SyncState.Error)
        assertEquals("Wrong passphrase. Sync disabled until corrected.", (state as SyncState.Error).message)
    }

    @Test
    fun `gating allows sync when remote and local both unencrypted`() = runTest {
        every { deviceRegistry.remoteEncrypted } returns false
        every { encryptionManager.isEncryptionEnabled } returns false
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns emptyList()

        syncManager.performSync()

        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `passphrase count is adopted as max of local and remote counts`() = runTest {
        val d = doc("doc-1")
        coEvery { documentStore.listDocuments() } returns Result.success(listOf(d))
        coEvery { documentStore.metadataVersion("doc-1") } returns 5
        coEvery { documentStore.metadataPassphrase("doc-1") } returns 0
        val r = remote("doc-1", version = 5, passphrase = 3, metadata = d)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(r)

        syncManager.performSync()

        coVerify { localDriveIndex.passphraseCount = 3 }
        coVerify(inverse = true) { uploadEngine.forceReEncryptDocument("doc-1") }
        assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `sync aborts with error when pullDocument fails`() = runTest {
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        val meta = StoredDocument(id = "remote-1", name = "Remote", createdAt = 0L, updatedAt = 0L)
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns listOf(remote("remote-1", version = 3, metadata = meta))
        coEvery { downloadEngine.pullDocument(any(), any()) } returns false

        syncManager.performSync()

        coVerify { retryHandler.onFailure() }
        assertTrue(syncManager.syncState.value is SyncState.Error)
    }
}
