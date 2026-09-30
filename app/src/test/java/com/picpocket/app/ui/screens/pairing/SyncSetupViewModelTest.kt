package com.picpocket.app.ui.screens.pairing

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.drive.PassphraseStore
import com.picpocket.app.drive.sync.FolderLocator
import com.picpocket.app.drive.sync.LocalDriveIndex
import com.picpocket.app.drive.sync.SyncManager
import com.picpocket.app.drive.sync.SyncSettings
import com.picpocket.app.drive.sync.SyncSetupCodec
import com.picpocket.app.drive.sync.SyncSetupPayload
import com.picpocket.app.util.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@ExperimentalCoroutinesApi
class SyncSetupViewModelTest {

    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val localDriveIndex = mockk<LocalDriveIndex>()
    private val encryptionManager = mockk<EncryptionManager>(relaxed = true)
    private val passphraseStore = mockk<PassphraseStore>(relaxed = true)
    private val syncManager = mockk<SyncManager>(relaxed = true)
    private val syncSettings = mockk<SyncSettings>(relaxed = true)

    private val treeUri = "content://com.example.documents/tree/primary%3APicPocketTest"

    private fun stubBase(
        folderUri: String = "",
        folderName: String = "",
        passphrase: String? = null,
        passCount: Int = 0,
    ) {
        every { localDriveIndex.getLocalDeviceId() } returns "dev-b"
        every { localDriveIndex.getRootTreeUri() } returns folderUri
        every { localDriveIndex.getRootFolderName() } returns folderName
        every { localDriveIndex.getDevices() } returns emptyMap()
        every { localDriveIndex.passphraseCount } returns passCount
        every { localDriveIndex.passphraseCount = any() } returns Unit
        every { localDriveIndex.setRootTreeUri(any()) } returns Unit
        every { localDriveIndex.setRootFolderName(any()) } returns Unit
        every { localDriveIndex.setRootFolderId(any()) } returns Unit
        every { localDriveIndex.setDevice(any(), any()) } returns Unit
        every { localDriveIndex.removeDevice(any()) } returns Unit
        every { passphraseStore.getPassphrase() } returns passphrase
        coEvery { syncManager.performSync() } returns Unit
    }

    private fun viewModel() = SyncSetupViewModel(app, localDriveIndex, encryptionManager, passphraseStore, syncManager, syncSettings)

    @Test
    fun `no folder yields no share code`() {
        stubBase()
        val vm = viewModel()
        assertNull(vm.uiState.value.shareCode)
    }

    @Test
    fun `share code carries the folder and the passphrase`() {
        stubBase(folderUri = treeUri, folderName = "PicPocketTest", passphrase = "pw", passCount = 2)
        val vm = viewModel()

        val payload = SyncSetupCodec.decode(vm.uiState.value.shareCode!!).getOrThrow()

        assertEquals("com.example.documents", payload.folder.authority)
        assertEquals("primary:PicPocketTest", payload.folder.documentId)
        assertEquals("pw", payload.passphrase)
        assertEquals(2, payload.passphraseCount)
        assertTrue(vm.uiState.value.passphraseIncluded)
    }

    @Test
    fun `a valid code sets a pending setup`() {
        stubBase()
        val vm = viewModel()
        val code = SyncSetupCodec.encode(
            SyncSetupPayload(folder = FolderLocator("com.example.documents", "primary:PicPocketTest", "PicPocketTest")),
        )

        vm.submitCode(code)

        assertTrue(vm.uiState.value.pendingSetup != null)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `a malformed code shows an error and changes nothing`() {
        stubBase()
        val vm = viewModel()

        vm.submitCode("not a picpocket code")

        assertNull(vm.uiState.value.pendingSetup)
        assertTrue(vm.uiState.value.message != null)
        verify(exactly = 0) { localDriveIndex.setRootTreeUri(any()) }
    }

    @Test
    fun `applying a setup stores the folder, passphrase, generation and enables sync`() = runTest {
        stubBase()
        val vm = viewModel()
        val folder = FolderLocator("com.example.documents", "primary:PicPocketTest", "PicPocketTest")
        vm.submitCode(SyncSetupCodec.encode(SyncSetupPayload(deviceId = "dev-a", deviceName = "A", folder = folder, passphrase = "pw", passphraseCount = 2)))

        vm.applyFolder(Uri.parse(treeUri))
        coroutineRule.dispatcher.scheduler.advanceUntilIdle()

        verify { localDriveIndex.setRootTreeUri(treeUri) }
        verify { localDriveIndex.setRootFolderName("PicPocketTest") }
        verify { localDriveIndex.setRootFolderId("primary:PicPocketTest") }
        verify { encryptionManager.setPassphrase("pw") }
        verify { passphraseStore.savePassphrase("pw") }
        verify { localDriveIndex.passphraseCount = 2 }
        verify { syncSettings.syncEnabled = true }
        coVerify { syncManager.performSync() }
        assertNull(vm.uiState.value.pendingSetup)
    }

    @Test
    fun `cancelling the folder picker changes nothing`() {
        stubBase()
        val vm = viewModel()
        val folder = FolderLocator("com.example.documents", "primary:PicPocketTest", "PicPocketTest")
        vm.submitCode(SyncSetupCodec.encode(SyncSetupPayload(folder = folder, passphrase = "pw", passphraseCount = 1)))

        vm.applyFolder(null)

        verify(exactly = 0) { localDriveIndex.setRootTreeUri(any()) }
        verify(exactly = 0) { passphraseStore.savePassphrase(any()) }
        assertNull(vm.uiState.value.pendingSetup)
        assertTrue(vm.uiState.value.message != null)
    }
}
