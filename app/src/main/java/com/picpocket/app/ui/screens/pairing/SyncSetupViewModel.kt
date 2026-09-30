package com.picpocket.app.ui.screens.pairing

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.drive.PassphraseStore
import com.picpocket.app.drive.sync.DeviceInfo
import com.picpocket.app.drive.sync.FolderLocator
import com.picpocket.app.drive.sync.LocalDriveIndex
import com.picpocket.app.drive.sync.SyncManager
import com.picpocket.app.drive.sync.SyncSettings
import com.picpocket.app.drive.sync.SyncSetupCodec
import com.picpocket.app.drive.sync.SyncSetupPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncSetupUiState(
    val localDeviceId: String = "",
    val localDeviceName: String = "",
    val pairedDevices: Map<String, DeviceInfo> = emptyMap(),
    /** The setup code to share, or null when no sync folder is configured. */
    val shareCode: String? = null,
    val passphraseIncluded: Boolean = false,
    /** A parsed setup code awaiting the user to confirm the folder in the SAF picker. */
    val pendingSetup: SyncSetupPayload? = null,
    val pastedCode: String = "",
    val message: String? = null,
)

@HiltViewModel
class SyncSetupViewModel @Inject constructor(
    application: Application,
    private val localDriveIndex: LocalDriveIndex,
    private val encryptionManager: EncryptionManager,
    private val passphraseStore: PassphraseStore,
    private val syncManager: SyncManager,
    private val syncSettings: SyncSettings,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SyncSetupUiState())
    val uiState: StateFlow<SyncSetupUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val deviceId = localDriveIndex.getLocalDeviceId()
        val deviceName = Build.MODEL
        val folder = FolderLocator.fromTreeUri(
            localDriveIndex.getRootTreeUri(),
            localDriveIndex.getRootFolderName(),
        )
        val passphrase = passphraseStore.getPassphrase()?.takeIf { it.isNotBlank() }
        val shareCode = folder?.let {
            SyncSetupCodec.encode(
                SyncSetupPayload(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    folder = it,
                    passphrase = passphrase,
                    passphraseCount = localDriveIndex.passphraseCount,
                ),
            )
        }
        _uiState.update {
            it.copy(
                localDeviceId = deviceId,
                localDeviceName = deviceName,
                pairedDevices = localDriveIndex.getDevices().filterKeys { id -> id != deviceId },
                shareCode = shareCode,
                passphraseIncluded = passphrase != null,
            )
        }
    }

    fun onPastedCodeChange(value: String) = _uiState.update { it.copy(pastedCode = value) }

    /** Parses a scanned or pasted code; on success the UI launches the folder picker. */
    fun submitCode(code: String) {
        if (code.isBlank()) {
            _uiState.update { it.copy(message = "Enter or scan a setup code") }
            return
        }
        SyncSetupCodec.decode(code).fold(
            onSuccess = { payload -> _uiState.update { it.copy(pendingSetup = payload, message = null) } },
            onFailure = { error -> _uiState.update { it.copy(message = error.message ?: "Invalid setup code") } },
        )
    }

    fun cancelPendingSetup() = _uiState.update { it.copy(pendingSetup = null) }

    /** Applies the user-confirmed SAF folder for the pending setup. */
    fun applyFolder(uri: Uri?) {
        val payload = _uiState.value.pendingSetup ?: return
        if (uri == null) {
            _uiState.update { it.copy(pendingSetup = null, message = "Folder selection cancelled") }
            return
        }
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val name = DocumentFile.fromTreeUri(app, uri)?.name
            ?: payload.folder.displayName.ifBlank { "Folder" }
        localDriveIndex.setRootTreeUri(uri.toString())
        localDriveIndex.setRootFolderName(name)
        localDriveIndex.setRootFolderId(
            FolderLocator.fromTreeUri(uri.toString(), name)?.documentId ?: payload.folder.documentId,
        )
        if (payload.deviceId.isNotBlank() && localDriveIndex.getDevices()[payload.deviceId] == null) {
            val now = System.currentTimeMillis()
            localDriveIndex.setDevice(payload.deviceId, DeviceInfo(payload.deviceName, now, now))
        }
        if (!payload.passphrase.isNullOrBlank()) {
            encryptionManager.setPassphrase(payload.passphrase)
            passphraseStore.savePassphrase(payload.passphrase)
            localDriveIndex.passphraseCount = payload.passphraseCount
        }
        syncSettings.syncEnabled = true
        _uiState.update { it.copy(pendingSetup = null, pastedCode = "", message = "Sync setup applied") }
        viewModelScope.launch { syncManager.performSync() }
        refresh()
    }

    fun unpairDevice(deviceId: String) {
        localDriveIndex.removeDevice(deviceId)
        refresh()
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}
