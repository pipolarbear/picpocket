package com.picpocket.app.ui.screens.detail

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import com.picpocket.app.domain.collate.CollateAxis
import com.picpocket.app.domain.collate.CollateLayout
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun CollateEditorScreen(state: DetailUiState, viewModel: DocumentDetailViewModel) {
    Dialog(
        onDismissRequest = { viewModel.hideCollateDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.collatePreview != null) "Adjust merge" else "Collate pages",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.collatePreview == null) {
                        TextButton(
                            onClick = { viewModel.buildCollatePreview() },
                            enabled = state.collateSelection.size >= 2 && !state.collateBusy,
                            modifier = Modifier.testTag("collate_preview"),
                        ) { Text("Preview") }
                    } else {
                        TextButton(
                            onClick = { viewModel.saveCollate() },
                            enabled = !state.collateBusy,
                            modifier = Modifier.testTag("collate_save"),
                        ) { Text("Save") }
                    }
                    TextButton(onClick = { viewModel.hideCollateDialog() }) { Text("Cancel") }
                }

                state.collateError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.collateBusy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (state.collatePreview == null) {
                    CollateSelectionStep(state, viewModel)
                } else {
                    CollateAdjustStep(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun CollateSelectionStep(state: DetailUiState, viewModel: DocumentDetailViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Select the pages to merge into one continuous image.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val allSelected = state.pages.isNotEmpty() && state.collateSelection.size == state.pages.size
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (allSelected) viewModel.clearCollateSelection() else viewModel.selectAllCollate()
                    },
            ) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { if (it) viewModel.selectAllCollate() else viewModel.clearCollateSelection() },
                    modifier = Modifier.testTag("collate_select_all"),
                )
                Text("Select all", style = MaterialTheme.typography.labelLarge)
            }
            state.pages.forEach { page ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("collate_page_${page.pageNumber}")
                        .clickable { viewModel.toggleCollateSelection(page.pageNumber) },
                ) {
                    Checkbox(
                        checked = page.pageNumber in state.collateSelection,
                        onCheckedChange = { viewModel.toggleCollateSelection(page.pageNumber) },
                    )
                    Text("Page ${page.pageNumber}")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Layout", style = MaterialTheme.typography.labelLarge)
        CollateLayoutChips(state, viewModel)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.collateMatchSizes,
                onCheckedChange = { viewModel.setCollateMatchSizes(it) },
                modifier = Modifier.testTag("collate_match_sizes"),
            )
            Spacer(Modifier.width(8.dp))
            Text("Match sizes")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.collateRemoveSources,
                onCheckedChange = { viewModel.setCollateRemoveSources(it) },
                modifier = Modifier.testTag("collate_remove_sources"),
            )
            Spacer(Modifier.width(8.dp))
            Text("Remove source pages after collating")
        }
    }
}

@Composable
private fun CollateAdjustStep(state: DetailUiState, viewModel: DocumentDetailViewModel) {
    val preview = state.collatePreview
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.collateOrder.size >= 2) {
            Text("Order", style = MaterialTheme.typography.labelLarge)
            SourceRail(state, viewModel)
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "Drag a seam to line the pages up, or pinch to zoom.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        if (preview != null) {
            CollatePreview(
                bitmap = preview,
                axis = state.collateAxis,
                joints = state.collateJoints,
                resetToken = state.collateResetToken,
                onNudgeJoint = { joint, delta -> viewModel.nudgeCollateJoint(joint, delta) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Layout", style = MaterialTheme.typography.labelLarge)
        CollateLayoutChips(state, viewModel)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.collateMatchSizes,
                onClick = { viewModel.setCollateMatchSizes(!state.collateMatchSizes) },
                label = { Text("Match sizes") },
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { viewModel.resetCollate() },
                modifier = Modifier.testTag("collate_reset"),
            ) { Text("Reset") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.collateRemoveSources,
                onCheckedChange = { viewModel.setCollateRemoveSources(it) },
                modifier = Modifier.testTag("collate_remove_sources"),
            )
            Spacer(Modifier.width(8.dp))
            Text("Remove source pages after collating")
        }
    }
}

@Composable
private fun CollateLayoutChips(state: DetailUiState, viewModel: DocumentDetailViewModel) {
    Row {
        FilterChip(
            selected = state.collateLayout == CollateLayout.AUTO,
            onClick = { viewModel.setCollateLayout(CollateLayout.AUTO) },
            label = { Text(autoLabel(state)) },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = state.collateLayout == CollateLayout.HORIZONTAL,
            onClick = { viewModel.setCollateLayout(CollateLayout.HORIZONTAL) },
            label = { Text("Horizontal") },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = state.collateLayout == CollateLayout.VERTICAL,
            onClick = { viewModel.setCollateLayout(CollateLayout.VERTICAL) },
            label = { Text("Vertical") },
        )
    }
}

private fun autoLabel(state: DetailUiState): String =
    if (state.collateLayout == CollateLayout.AUTO && state.collatePreview != null) {
        if (state.collateAxis == CollateAxis.VERTICAL) "Auto (\u2195 vertical)" else "Auto (\u2194 horizontal)"
    } else {
        "Auto"
    }

@Composable
private fun SourceRail(state: DetailUiState, viewModel: DocumentDetailViewModel) {
    val pages = state.collateOrder.mapNotNull { number -> state.pages.find { it.pageNumber == number } }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to -> viewModel.moveCollateSource(from.index, to.index) },
    )
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(pages, key = { _, page -> page.pageNumber }) { index, page ->
            ReorderableItem(reorderState, key = page.pageNumber) { _ ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(2.dp),
                ) {
                    Card(
                        modifier = Modifier
                            .size(64.dp)
                            .draggableHandle(),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        SubcomposeAsyncImage(
                            model = page.imageUri,
                            contentDescription = "Source ${index + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CollatePreview(
    bitmap: Bitmap,
    axis: CollateAxis,
    joints: List<Int>,
    resetToken: Int,
    onNudgeJoint: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var scale by remember(resetToken) { mutableFloatStateOf(1f) }
    var offset by remember(resetToken) { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }

    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()
    val fit = if (container.width == 0 || container.height == 0) 1f else {
        minOf(container.width / bmpW, container.height / bmpH)
    }
    val displayW = bmpW * fit
    val displayH = bmpH * fit
    val originX = (container.width - displayW) / 2f
    val originY = (container.height - displayH) / 2f

    Box(
        modifier = modifier
            .onSizeChanged { container = it }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    val maxPanX = ((scale - 1f) * displayW / 2f).coerceAtLeast(0f)
                    val maxPanY = ((scale - 1f) * displayH / 2f).coerceAtLeast(0f)
                    offset = Offset(
                        (offset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                        (offset.y + pan.y).coerceIn(-maxPanY, maxPanY),
                    )
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            Image(
                bitmap = image,
                contentDescription = "Merged preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            joints.forEachIndexed { jointIndex, jointCoord ->
                val handleModifier = if (axis == CollateAxis.VERTICAL) {
                    Modifier
                        .offset { IntOffset(0, (originY + jointCoord * fit - 20.dp.toPx()).roundToInt()) }
                        .fillMaxWidth()
                        .height(40.dp)
                } else {
                    Modifier
                        .offset { IntOffset((originX + jointCoord * fit - 20.dp.toPx()).roundToInt(), 0) }
                        .fillMaxHeight()
                        .width(40.dp)
                }
                Box(
                    modifier = handleModifier.pointerInput(bitmap, axis, scale, jointIndex, jointCoord) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val denom = (fit * scale).coerceAtLeast(0.0001f)
                            val delta = if (axis == CollateAxis.VERTICAL) drag.y / denom else drag.x / denom
                            onNudgeJoint(jointIndex, delta.roundToInt())
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .then(
                                if (axis == CollateAxis.VERTICAL) {
                                    Modifier.fillMaxWidth().height(2.dp)
                                } else {
                                    Modifier.fillMaxHeight().width(2.dp)
                                },
                            )
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}
