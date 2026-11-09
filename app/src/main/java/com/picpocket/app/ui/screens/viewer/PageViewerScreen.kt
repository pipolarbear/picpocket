package com.picpocket.app.ui.screens.viewer

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.SubcomposeAsyncImage
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.PageKind
import com.picpocket.app.data.model.RedactionRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val PREVIEW_RENDER_WIDTH = 1600

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PageViewerScreen(
    documentId: DocumentId,
    initialPageIndex: Int,
    onNavigateBack: () -> Unit,
    initialRedactMode: Boolean = false,
    viewModel: PageViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(documentId) {
        viewModel.loadPages(documentId, initialPageIndex)
    }

    val pagerState = rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { state.pages.size },
    )

    LaunchedEffect(pagerState.currentPage) {
        viewModel.setPageIndex(pagerState.currentPage)
    }

    var redacting by remember { mutableStateOf(initialRedactMode) }
    var showControls by remember { mutableStateOf(true) }
    var previewRedacted by remember(documentId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hasRedactions = state.pages.any { it.redactions.isNotEmpty() }

    // Preview and marking are separate modes; entering marking shows the original.
    LaunchedEffect(redacting) {
        if (redacting) previewRedacted = false
    }

    LaunchedEffect(showControls, redacting) {
        if (showControls && !redacting) {
            delay(3000)
            showControls = false
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showControls || redacting) {
                TopAppBar(
                    title = {
                        Text(
                            if (redacting) "Mark areas to hide"
                            else "${pagerState.currentPage + 1} / ${state.pages.size}",
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (redacting) {
                            TextButton(
                                onClick = {
                                    state.pages.getOrNull(pagerState.currentPage)?.let {
                                        viewModel.clearRedactions(it)
                                    }
                                },
                                modifier = Modifier.testTag("clear_page_redactions"),
                            ) { Text("Clear page") }
                            TextButton(
                                onClick = { viewModel.clearAllRedactions() },
                                modifier = Modifier.testTag("clear_all_redactions"),
                            ) { Text("Clear all") }
                        }
                        if (!redacting && hasRedactions) {
                            IconButton(
                                onClick = { previewRedacted = !previewRedacted },
                                modifier = Modifier.testTag("toggle_redacted_preview"),
                            ) {
                                Icon(
                                    if (previewRedacted) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (previewRedacted) {
                                        "Hide redacted preview"
                                    } else {
                                        "Preview redacted"
                                    },
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                redacting = !redacting
                                showControls = true
                            },
                            modifier = Modifier.testTag("toggle_redact_mode"),
                        ) {
                            Icon(
                                if (redacting) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (redacting) "Done marking areas" else "Mark areas to hide",
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (redacting) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    pagerState.scrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                }
                            },
                            enabled = pagerState.currentPage > 0,
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous page")
                        }
                        Text(
                            "${pagerState.currentPage + 1} / ${state.pages.size}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    pagerState.scrollToPage(
                                        (pagerState.currentPage + 1).coerceAtMost(state.pages.size - 1),
                                    )
                                }
                            },
                            enabled = pagerState.currentPage < state.pages.size - 1,
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next page")
                        }
                    }
                    Text(
                        "Drag to mark an area. Tap a marked area to remove it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else if (showControls) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${pagerState.currentPage + 1} / ${state.pages.size}",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            // Drawing must not fight the pager; a marked page is edited in place.
            userScrollEnabled = !redacting,
        ) { pageIndex ->
            val page = state.pages.getOrNull(pageIndex) ?: return@HorizontalPager
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(showControls, redacting) {
                        if (redacting) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown()
                            val up = waitForUpOrCancellation()
                            if (up != null) showControls = true
                        }
                    },
            ) {
                ZoomablePage(
                    page = page,
                    redacting = redacting,
                    previewRedacted = previewRedacted && page.redactions.isNotEmpty(),
                    loadBitmap = { width -> viewModel.renderPage(page, width) },
                    loadRedactedPreview = { width -> viewModel.redactedPreview(page, width) },
                    onAddRedaction = { rect -> viewModel.addRedaction(page, rect) },
                    onRemoveRedaction = { index -> viewModel.removeRedaction(page, index) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomablePage(
    page: Page,
    redacting: Boolean,
    previewRedacted: Boolean = false,
    loadBitmap: (suspend (Int) -> android.graphics.Bitmap?)? = null,
    loadRedactedPreview: (suspend (Int) -> android.graphics.Bitmap?)? = null,
    onAddRedaction: (RedactionRect) -> Unit = {},
    onRemoveRedaction: (Int) -> Unit = {},
) {
    var imageWidth by remember(page.imageUri) { mutableIntStateOf(0) }
    var imageHeight by remember(page.imageUri) { mutableIntStateOf(0) }
    var renderedBitmap by remember(page.imageUri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var renderWidth by remember(page.imageUri) { mutableIntStateOf(0) }

    LaunchedEffect(page.imageUri) {
        if (page.kind != PageKind.PDF) {
            withContext(Dispatchers.IO) {
                val path = Uri.parse(page.imageUri).path
                if (path != null) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, opts)
                    imageWidth = opts.outWidth
                    imageHeight = opts.outHeight
                }
            }
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var draft by remember { mutableStateOf<RedactionRect?>(null) }
    var previewBitmap by remember(page.imageUri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    // The local preview uses the same burner as the export, so what is shown is
    // what the exported PDF contains. The original file is never touched.
    LaunchedEffect(previewRedacted, page.imageUri, page.redactions) {
        if (previewRedacted && page.redactions.isNotEmpty() && loadRedactedPreview != null) {
            val rendered = withContext(Dispatchers.IO) { loadRedactedPreview(PREVIEW_RENDER_WIDTH) }
            previewBitmap = rendered
            if (rendered != null) {
                imageWidth = rendered.width
                imageHeight = rendered.height
            }
        } else {
            // Dropped, not recycled: the draw pass may still hold it for a frame.
            previewBitmap = null
        }
    }

    // Marking happens at 1:1 so a drag maps directly onto the page.
    LaunchedEffect(redacting) {
        if (redacting) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }

    // Render PDF pages sharper as the zoom settles (debounced by LaunchedEffect restarts).
    LaunchedEffect(page.imageUri, scale) {
        if (page.kind == PageKind.PDF && loadBitmap != null) {
            val target = if (scale > 1.4f) 2400 else 1600
            if (target != renderWidth) {
                delay(250)
                val bitmap = withContext(Dispatchers.IO) { loadBitmap(target) }
                if (bitmap != null) {
                    renderedBitmap?.recycle()
                    renderedBitmap = bitmap
                    renderWidth = target
                    imageWidth = bitmap.width
                    imageHeight = bitmap.height
                }
            }
        }
    }

    val imageRect = remember(containerSize, imageWidth, imageHeight) {
        fittedRect(containerSize, imageWidth, imageHeight)
    }
    val outlineColor = MaterialTheme.colorScheme.error

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .then(
                if (redacting) {
                    Modifier.pointerInput(page.pageNumber, page.redactions, imageRect) {
                        if (imageRect.width <= 0f || imageRect.height <= 0f) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var current = down.position
                            var dragged = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                current = change.position
                                if ((current - down.position).getDistance() > 8f) dragged = true
                                if (dragged) {
                                    change.consume()
                                    draft = rectBetween(down.position, current, imageRect)
                                }
                                if (!change.pressed) break
                            }
                            if (dragged) {
                                val rect = rectBetween(down.position, current, imageRect)
                                draft = null
                                if (rect.isUsable()) onAddRedaction(rect)
                            } else {
                                val index = page.redactions.indexOfLast {
                                    it.contains(normalized(down.position, imageRect))
                                }
                                if (index >= 0) onRemoveRedaction(index)
                            }
                        }
                    }
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var wasTap = true
                            val downTime = System.currentTimeMillis()

                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.fold(true) { a, c -> a && c.isConsumed }) break

                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val isMultiTouch = event.changes.size > 1

                                if (isMultiTouch || scale > 1f) {
                                    scale = (scale * zoomChange).coerceIn(1f, 5f)
                                    val w = imageWidth.coerceAtLeast(1)
                                    val h = imageHeight.coerceAtLeast(1)
                                    val fitScale = minOf(
                                        containerSize.width.toFloat() / w,
                                        containerSize.height.toFloat() / h,
                                    )
                                    val displayedWidth = w * fitScale
                                    val displayedHeight = h * fitScale
                                    val maxPanX = ((scale - 1f) * displayedWidth / 2f).coerceAtLeast(0f)
                                    val maxPanY = ((scale - 1f) * displayedHeight / 2f).coerceAtLeast(0f)
                                    offsetX = (offsetX + panChange.x).coerceIn(-maxPanX, maxPanX)
                                    offsetY = (offsetY + panChange.y).coerceIn(-maxPanY, maxPanY)
                                    event.changes.forEach { change ->
                                        if (change.position != change.previousPosition) change.consume()
                                    }
                                }

                                if (isMultiTouch || event.changes.any { (it.position - it.previousPosition).getDistance() > 20f }) {
                                    wasTap = false
                                }
                            } while (event.changes.any { it.pressed })

                            if (wasTap && System.currentTimeMillis() - downTime < 300L) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 400L) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                lastTapTime = now
                            }
                        }
                    }
                },
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
                clip = false
            },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = previewBitmap ?: renderedBitmap
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${page.pageNumber}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else if (page.kind == PageKind.PDF && loadBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        } else {
            SubcomposeAsyncImage(
                model = page.imageUri,
                contentDescription = "Page ${page.pageNumber}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Failed to load page", style = MaterialTheme.typography.bodyMedium)
                    }
                },
            )
        }

        if (!previewRedacted && imageRect.width > 0f && imageRect.height > 0f) {
            Canvas(Modifier.fillMaxSize().testTag("redaction_overlay")) {
                val marked = if (draft != null) page.redactions + draft!! else page.redactions
                for (rect in marked) {
                    val topLeft = Offset(
                        imageRect.left + rect.x * imageRect.width,
                        imageRect.top + rect.y * imageRect.height,
                    )
                    val size = Size(rect.w * imageRect.width, rect.h * imageRect.height)
                    if (redacting) {
                        drawRect(Color.Black.copy(alpha = 0.45f), topLeft, size)
                    }
                    drawRect(outlineColor, topLeft, size, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

/** The rectangle the fitted page occupies inside the container, in container pixels. */
private fun fittedRect(container: IntSize, imageWidth: Int, imageHeight: Int): Rect {
    if (container.width <= 0 || container.height <= 0 || imageWidth <= 0 || imageHeight <= 0) {
        return Rect.Zero
    }
    val fitScale = minOf(
        container.width.toFloat() / imageWidth,
        container.height.toFloat() / imageHeight,
    )
    val width = imageWidth * fitScale
    val height = imageHeight * fitScale
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun normalized(position: Offset, imageRect: Rect): Offset = Offset(
    ((position.x - imageRect.left) / imageRect.width).coerceIn(0f, 1f),
    ((position.y - imageRect.top) / imageRect.height).coerceIn(0f, 1f),
)

private fun rectBetween(start: Offset, end: Offset, imageRect: Rect): RedactionRect {
    val from = normalized(start, imageRect)
    val to = normalized(end, imageRect)
    return RedactionRect.fromDrag(from.x, from.y, to.x, to.y)
}

private fun RedactionRect.contains(point: Offset): Boolean =
    point.x >= x && point.x <= right && point.y >= y && point.y <= bottom
