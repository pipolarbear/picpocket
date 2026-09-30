package com.picpocket.app.ui.screens.pairing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.picpocket.app.drive.sync.DeviceInfo
import com.picpocket.app.util.QrCodeGenerator
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSetupScreen(
    onNavigateBack: () -> Unit,
    viewModel: SyncSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(OpenDocumentTreeAt()) { uri ->
        viewModel.applyFolder(uri)
    }
    LaunchedEffect(state.pendingSetup) {
        state.pendingSetup?.let { folderPicker.launch(it.folder.treeUri()) }
    }

    SyncSetupContent(
        state = state,
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState,
        onPasteChange = viewModel::onPastedCodeChange,
        onApplyPasted = { viewModel.submitCode(state.pastedCode) },
        onCodeScanned = viewModel::submitCode,
        onUnpair = viewModel::unpairDevice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSetupContent(
    state: SyncSetupUiState,
    onNavigateBack: () -> Unit,
    onPasteChange: (String) -> Unit,
    onApplyPasted: () -> Unit,
    onCodeScanned: (String) -> Unit,
    onUnpair: (String) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var showShareQr by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync setup") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("This Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(state.localDeviceName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "ID: ${state.localDeviceId.take(8)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row {
                            OutlinedButton(
                                onClick = { showShareQr = true },
                                enabled = state.shareCode != null,
                                modifier = Modifier.testTag("share_setup"),
                            ) {
                                Icon(Icons.Default.DevicesOther, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Share setup")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { showScan = true },
                                modifier = Modifier.testTag("scan_setup"),
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Scan setup")
                            }
                        }
                        if (state.shareCode == null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Configure a sync folder on the Sync screen to share a setup code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Join another device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Scan the other device's setup code, or paste it below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.pastedCode,
                            onValueChange = onPasteChange,
                            label = { Text("Setup code") },
                            modifier = Modifier.fillMaxWidth().testTag("pasted_code"),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onApplyPasted,
                            enabled = state.pastedCode.isNotBlank(),
                            modifier = Modifier.testTag("apply_code"),
                        ) {
                            Text("Apply")
                        }
                    }
                }
            }

            if (state.pairedDevices.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No paired devices",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            } else {
                item {
                    Text(
                        "Paired Devices (${state.pairedDevices.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(state.pairedDevices.entries.toList(), key = { it.key }) { (deviceId, info) ->
                    PairedDeviceCard(deviceId = deviceId, info = info, onUnpair = { onUnpair(deviceId) })
                }
            }
        }
    }

    if (showScan) {
        QrScannerSheet(
            onScanResult = { data ->
                onCodeScanned(data)
                showScan = false
            },
            onDismiss = { showScan = false },
        )
    }

    if (showShareQr) {
        state.shareCode?.let { code ->
            SetupQrSheet(
                code = code,
                passphraseIncluded = state.passphraseIncluded,
                onDismiss = { showShareQr = false },
            )
        }
    }
}

@Composable
private fun PairedDeviceCard(
    deviceId: String,
    info: DeviceInfo,
    onUnpair: () -> Unit,
) {
    var showUnpairConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "ID: ${deviceId.take(8)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showUnpairConfirm = true }) {
                Text("Unpair", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text("Unpair Device?") },
            text = { Text("This device will no longer sync deletions. Already acknowledged tombstones are unaffected.") },
            confirmButton = {
                TextButton(onClick = { onUnpair(); showUnpairConfirm = false }) {
                    Text("Unpair", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupQrSheet(
    code: String,
    passphraseIncluded: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SetupCodeCard(code = code, passphraseIncluded = passphraseIncluded)
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

/** The setup QR and its copyable text code. */
@Composable
fun SetupCodeCard(
    code: String,
    passphraseIncluded: Boolean,
) {
    val qrBitmap = remember(code) { QrCodeGenerator.generate(code, 640) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Sync setup code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (passphraseIncluded) {
                "Scan or paste this on your other device. It includes your encryption passphrase."
            } else {
                "Scan or paste this on your other device."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        qrBitmap?.let { bitmap ->
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Setup QR code",
                modifier = Modifier.size(240.dp).clip(RoundedCornerShape(8.dp)),
            )
        }

        Spacer(Modifier.height(16.dp))
        SelectionContainer {
            Text(
                code,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("setup_code"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerSheet(
    onScanResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    var barcodeValue by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(barcodeValue) {
        barcodeValue?.let { onScanResult(it) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Scan Setup Code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            if (!hasCameraPermission) {
                Text(
                    "Camera permission required to scan QR codes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(modifier = Modifier.size(280.dp).clip(RoundedCornerShape(12.dp))) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy: ImageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                        BarcodeScanning.getClient().process(inputImage)
                                            .addOnSuccessListener { barcodes ->
                                                for (barcode in barcodes) {
                                                    barcode.rawValue?.let { value -> barcodeValue = value }
                                                }
                                            }
                                            .addOnCompleteListener { imageProxy.close() }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                                cameraProvider.bindToLifecycle(
                                    ctx as androidx.lifecycle.LifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis,
                                )
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

/** Opens the SAF tree picker pre-navigated to [input] when the provider supports it. */
private class OpenDocumentTreeAt : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        if (input != null) {
            // DocumentsContract.EXTRA_INITIAL_URI (API 26+); literal to avoid a Robolectric stub gap.
            intent.putExtra("android.provider.extra.INITIAL_URI", input)
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
