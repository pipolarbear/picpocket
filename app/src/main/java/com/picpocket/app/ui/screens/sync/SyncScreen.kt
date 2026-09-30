package com.picpocket.app.ui.screens.sync

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.picpocket.app.drive.SyncState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onNavigateBack: () -> Unit,
    onSyncSetupClick: () -> Unit = {},
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val actionState by viewModel.actionState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.verifyConnection()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val syncError = (state.syncState as? SyncState.Error)?.message
    LaunchedEffect(syncError) {
        syncError?.let { snackbarHostState.showSnackbar(it) }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        viewModel.handleFolderPickerResult(uri)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
        ) {
            when (state.connectionState) {
                is ConnectionState.Loading -> {
                    CircularProgressIndicator()
                }
                is ConnectionState.Disconnected -> {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        "Select a folder to sync your documents across devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Select Folder")
                    }
                    Spacer(Modifier.height(16.dp))
                    SyncSetupEntryCard(onClick = onSyncSetupClick)
                }
                is ConnectionState.Connected -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Drive", style = MaterialTheme.typography.titleMedium)
                                    if (state.folderName.isNotBlank()) {
                                        Text(
                                            state.folderName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Sync enabled", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = state.syncEnabled,
                                    onCheckedChange = { viewModel.toggleSync(it) },
                                    modifier = Modifier.semantics { contentDescription = "Toggle sync" },
                                )
                            }
                            if (!state.syncEnabled) {
                                Text(
                                    "Enable sync to start syncing",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row {
                                Button(
                                    onClick = { viewModel.syncNow() },
                                    enabled = state.syncEnabled && state.syncState !is SyncState.Syncing,
                                ) {
                                    if (state.syncState is SyncState.Syncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text("Sync Now")
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { viewModel.disconnect() }) {
                                    Text("Disconnect")
                                }
                            }
                            if (state.syncState is SyncState.Error) {
                                Text(
                                    text = (state.syncState as SyncState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            EncryptionSection(
                                encryptionEnabled = state.encryptionEnabled,
                                isSyncing = state.syncState is SyncState.Syncing,
                                onEnableEncryption = { viewModel.setEncryptionPassphrase(it) },
                                onDisableEncryption = { viewModel.disableEncryption() },
                                onChangePassphrase = { viewModel.setEncryptionPassphrase(it) },
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Sync Management", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Manage deletions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            if (state.trashCount > 0 || state.removedByOthersCount > 0) {
                                if (state.trashCount > 0) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text("${state.trashCount} Trash", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    HorizontalDivider()
                                }
                                if (state.removedByOthersCount > 0) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text("${state.removedByOthersCount} Removed by Others", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            } else {
                                Text(
                                    "No pending actions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    SyncSetupEntryCard(onClick = onSyncSetupClick)
                }
            }
        }
    }

    when (actionState) {
        is SyncActionState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissAction() },
                title = { Text("Sync") },
                text = { Text((actionState as SyncActionState.Error).message) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissAction()
                        folderPickerLauncher.launch(null)
                    }) {
                        Text("Try Again")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissAction() }) {
                        Text("Cancel")
                    }
                },
            )
        }
        else -> {}
    }
}

@Composable
private fun EncryptionSection(
    encryptionEnabled: Boolean,
    isSyncing: Boolean,
    onEnableEncryption: (String) -> Unit,
    onDisableEncryption: () -> Unit,
    onChangePassphrase: (String) -> Unit,
) {
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var showDisableConfirm by remember { mutableStateOf(false) }
    var passphraseInput by remember { mutableStateOf("") }
    var passphraseConfirm by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    if (showPassphraseDialog) {
        AlertDialog(
            onDismissRequest = {
                showPassphraseDialog = false
                passphraseInput = ""
                passphraseConfirm = ""
                showError = false
            },
            title = { Text(if (encryptionEnabled) "Change Passphrase" else "Set Encryption Passphrase") },
            text = {
                Column {
                    if (encryptionEnabled) {
                        Text(
                            "Enter a new passphrase to encrypt Drive files.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "Set a passphrase to encrypt all Drive files and filenames.\n\nYour device must have a lock screen configured.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passphraseInput,
                        onValueChange = { passphraseInput = it; showError = false },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        isError = showError,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphraseConfirm,
                        onValueChange = { passphraseConfirm = it; showError = false },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        isError = showError,
                    )
                    if (showError) {
                        Text(
                            "Passphrases do not match",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (passphraseInput == passphraseConfirm && passphraseInput.isNotBlank()) {
                            if (encryptionEnabled) {
                                onChangePassphrase(passphraseInput)
                            } else {
                                onEnableEncryption(passphraseInput)
                            }
                            showPassphraseDialog = false
                            passphraseInput = ""
                            passphraseConfirm = ""
                        } else {
                            showError = true
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPassphraseDialog = false
                    passphraseInput = ""
                    passphraseConfirm = ""
                    showError = false
                }) { Text("Cancel") }
            },
        )
    }

    if (showDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            title = { Text("Disable Encryption?") },
            text = {
                Text("This will decrypt all previously synced Drive files. Continue?")
            },
            confirmButton = {
                TextButton(onClick = { onDisableEncryption(); showDisableConfirm = false }) {
                    Text("Disable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirm = false }) { Text("Cancel") }
            },
        )
    }

    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("Encryption", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        if (encryptionEnabled) "Drive files are encrypted at rest"
        else "Drive files are stored in cleartext",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (encryptionEnabled) {
        Row {
            OutlinedButton(onClick = { showPassphraseDialog = true }, enabled = !isSyncing) {
                Text("Change Passphrase")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showDisableConfirm = true }, enabled = !isSyncing) {
                Text("Disable")
            }
        }
    } else {
        OutlinedButton(onClick = { showPassphraseDialog = true }, enabled = !isSyncing) {
            Text("Enable Encryption")
        }
    }
}

@Composable
private fun SyncSetupEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.DevicesOther,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Share or scan setup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Join this sync on another device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
