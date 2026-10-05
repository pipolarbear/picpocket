package com.picpocket.app.ui.screens.viewer

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import coil.compose.SubcomposeAsyncImage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.data.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PageViewerScreen(
    documentId: DocumentId,
    initialPageIndex: Int,
    onNavigateBack: () -> Unit,
    viewModel: PageViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

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

    var showControls by remember { mutableStateOf(true) }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    Scaffold(
        topBar = {
            if (showControls) {
                TopAppBar(
                    title = { Text("${pagerState.currentPage + 1} / ${state.pages.size}") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showControls) {
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
        ) { pageIndex ->
            val page = state.pages.getOrNull(pageIndex) ?: return@HorizontalPager
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(showControls) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val up = waitForUpOrCancellation()
                            if (up != null) showControls = true
                        }
                    },
            ) {
                ZoomablePage(page = page, loadBitmap = { width -> viewModel.renderPage(page, width) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomablePage(page: Page, loadBitmap: (suspend (Int) -> android.graphics.Bitmap?)? = null) {
    var imageWidth by remember(page.imageUri) { mutableIntStateOf(0) }
    var imageHeight by remember(page.imageUri) { mutableIntStateOf(0) }
    var renderedBitmap by remember(page.imageUri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(page.imageUri) {
        if (page.kind == com.picpocket.app.data.model.PageKind.PDF && loadBitmap != null) {
            val bitmap = withContext(Dispatchers.IO) { loadBitmap(1600) }
            renderedBitmap = bitmap
            if (bitmap != null) {
                imageWidth = bitmap.width
                imageHeight = bitmap.height
            }
        } else {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
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
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
                clip = false
            },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = renderedBitmap
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${page.pageNumber}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
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
    }
}
