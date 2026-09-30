package com.picpocket.app.ui.screens.sync

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.picpocket.app.drive.DriveAuthManager
import com.picpocket.app.drive.EncryptionManager
import com.picpocket.app.drive.PassphraseStore
import com.picpocket.app.drive.SyncState
import com.picpocket.app.drive.sync.DeviceRegistry
import com.picpocket.app.drive.sync.FolderLocator
import com.picpocket.app.drive.sync.LocalDriveIndex
import com.picpocket.app.drive.sync.RetryHandler
import com.picpocket.app.drive.sync.SyncManager
import com.picpocket.app.drive.sync.SyncSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(
    val connectionState: ConnectionState = ConnectionState.Loading,
    val syncEnabled: Boolean = false,
    val syncState: SyncState = SyncState.Idle,
    val folderName: String = "",
    val trashCount: Int = 0,
    val removedByOthersCount: Int = 0,
    val encryptionEnabled: Boolean = false,
)

sealed interface ConnectionState {
    data object Loading : ConnectionState
    data object Disconnected : ConnectionState
    data object Connected : ConnectionState
}

sealed interface SyncActionState {
    data object Idle : SyncActionState
    data class Error(val message: String) : SyncActionState
}

@HiltViewModel
class SyncViewModel @Inject constructor(
    application: Application,
    private val driveAuthManager: DriveAuthManager,
    private val syncManager: SyncManager,
    private val syncSettings: SyncSettings,
    private val localDriveIndex: LocalDriveIndex,
    private val deviceRegistry: DeviceRegistry,
    private val retryHandler: RetryHandler,
    private val encryptionManager: EncryptionManager,
    private val passphraseStore: PassphraseStore,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private val _actionState = MutableStateFlow<SyncActionState>(SyncActionState.Idle)
    val actionState: StateFlow<SyncActionState> = _actionState.asStateFlow()

    init {
        viewModelScope.launch {
            syncManager.syncState.collect { state ->
                _uiState.update { it.copy(syncState = state) }
            }
        }
        val savedPassphrase = passphraseStore.getPassphrase()
        if (!savedPassphrase.isNullOrBlank()) {
            encryptionManager.setPassphrase(savedPassphrase)
        }
        verifyConnection()
    }

    fun verifyConnection() {
        if (!localDriveIndex.hasValidFolder()) {
            _uiState.update { it.copy(connectionState = ConnectionState.Disconnected) }
            return
        }
        driveAuthManager.setConnected()
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.Connected,
                folderName = localDriveIndex.getRootFolderName(),
                syncEnabled = syncSettings.syncEnabled,
                trashCount = deviceRegistry.getMyDeleted().size,
                removedByOthersCount = deviceRegistry.getOthersDeleted().size,
                encryptionEnabled = encryptionManager.isEncryptionEnabled,
            )
        }
    }

    fun setEncryptionPassphrase(passphrase: String) {
        if (syncManager.syncState.value is SyncState.Syncing) return
        if (passphrase != passphraseStore.getPassphrase()) {
            localDriveIndex.passphraseCount = localDriveIndex.passphraseCount + 1
        }
        encryptionManager.setPassphrase(passphrase)
        passphraseStore.savePassphrase(passphrase)
        viewModelScope.launch {
            syncManager.performSync()
        }
        _uiState.update { it.copy(encryptionEnabled = true) }
    }

    fun disableEncryption() {
        if (syncManager.syncState.value is SyncState.Syncing) return
        localDriveIndex.passphraseCount = localDriveIndex.passphraseCount + 1
        encryptionManager.clearPassphrase()
        passphraseStore.clearPassphrase()
        viewModelScope.launch {
            syncManager.performSync()
        }
        _uiState.update { it.copy(encryptionEnabled = false) }
    }

    fun handleFolderPickerResult(uri: Uri?) {
        if (uri == null) {
            _actionState.value = SyncActionState.Error("Folder selection cancelled")
            return
        }
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        retryHandler.reset()
        localDriveIndex.setRootTreeUri(uri.toString())
        val folderName = DocumentFile.fromTreeUri(app, uri)?.name ?: "Folder"
        localDriveIndex.setRootFolderName(folderName)
        localDriveIndex.setRootFolderId(
            FolderLocator.fromTreeUri(uri.toString(), folderName)?.documentId ?: "",
        )
        _actionState.value = SyncActionState.Idle
        verifyConnection()
    }

    fun syncNow() {
        viewModelScope.launch {
            retryHandler.reset()
            syncManager.performSync()
        }
    }

    fun toggleSync(enabled: Boolean) {
        syncSettings.syncEnabled = enabled
        _uiState.update { it.copy(syncEnabled = enabled) }
    }

    fun disconnect() {
        localDriveIndex.clearFolder()
        driveAuthManager.signOut()
        _uiState.update { it.copy(connectionState = ConnectionState.Disconnected) }
    }

    fun dismissAction() {
        _actionState.value = SyncActionState.Idle
    }
}
