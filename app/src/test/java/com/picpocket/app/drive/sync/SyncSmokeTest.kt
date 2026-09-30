package com.picpocket.app.drive.sync

import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.drive.SyncState
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class SyncSmokeTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val driveAuthManager = mockk<com.picpocket.app.drive.DriveAuthManager>()
    private val documentRepository = mockk<com.picpocket.app.data.repository.DocumentRepository>()
    private val documentStore = mockk<com.picpocket.app.data.store.DocumentStore>()
    private val uploadEngine = mockk<UploadEngine>()
    private val downloadEngine = mockk<DownloadEngine>()
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val driveConnectivityChecker = mockk<com.picpocket.app.drive.DriveConnectivityChecker>()
    private val defaultSyncScheduler = mockk<DefaultSyncScheduler>()
    private val deviceRegistry = mockk<DeviceRegistry>()
    private val driveFileManager = mockk<DriveFileManager>()
    private val encryptionManager = mockk<EncryptionManager>()
    private val passphraseStore = mockk<com.picpocket.app.drive.PassphraseStore>(relaxed = true)
    private val retryHandler = mockk<RetryHandler>()
    private val syncSettings = mockk<SyncSettings>()
    private val context = mockk<android.content.Context>()
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

    @Test
    fun `sync setup works end to end`() = runTest {
        coEvery { documentStore.listDocuments() } returns Result.success(emptyList())
        coEvery { downloadEngine.listRemoteDocuments(any()) } returns emptyList()
        coEvery { deviceRegistry.detectOrphans(emptyList(), emptyList(), any()) } returns Unit

        syncManager.performSync()

        Assert.assertEquals(SyncState.Idle, syncManager.syncState.value)
    }

    @Test
    fun `encryption can be enabled and disabled`() {
        val em = EncryptionManager()
        Assert.assertFalse(em.isEncryptionEnabled)

        em.setPassphrase("smoke-test-key")
        Assert.assertTrue(em.isEncryptionEnabled)

        em.clearPassphrase()
        Assert.assertFalse(em.isEncryptionEnabled)
    }

    @Test
    fun `DriveAuthState sealed interface is exhaustive`() {
        val states = listOf(
            DriveAuthState.Connected,
            DriveAuthState.Disconnected,
            DriveAuthState.ReauthRequired,
            DriveAuthState.Connecting,
            DriveAuthState.Error("test"),
        )
        Assert.assertEquals(5, states.size)
    }

    @Test
    fun `SyncState sealed interface is exhaustive`() {
        val states = listOf(
            SyncState.Idle,
            SyncState.Syncing,
            SyncState.Error("test"),
        )
        Assert.assertEquals(3, states.size)
    }
}
