package com.picpocket.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.picpocket.app.data.model.Tag
import com.picpocket.app.ui.theme.TagColors
import com.picpocket.app.util.fuzzyMatch

enum class MatchMode(val label: String) {
    MATCH_ANY("Match any"),
    MATCH_ALL("Match all"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagSelectorSheet(
    allTags: List<Tag>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onCreateTag: (String) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Add Tags",
    showCreate: Boolean = true,
    matchMode: MatchMode? = null,
    onMatchModeChange: ((MatchMode) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, allTags) {
        if (query.isBlank()) allTags
        else allTags.filter { fuzzyMatch(query, it.name) }
    }

    val selectedTags = remember(selectedTagIds, allTags) {
        allTags.filter { it.id in selectedTagIds }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
            )

            if (matchMode != null && onMatchModeChange != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MatchMode.entries.forEach { mode ->
                        val isSelected = mode == matchMode
                        TextButton(
                            onClick = { onMatchModeChange(mode) },
                            content = {
                                Text(
                                    mode.label,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (selectedTags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectedTags.forEach { tag ->
                        TagChip(
                            tag = tag,
                            onRemove = { onToggleTag(tag.id) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search or create tag...") },
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

            if (showCreate && query.isNotBlank() && filtered.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCreateTag(query.trim()); query = "" }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Create \"${query.trim()}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                ) {
                    items(filtered, key = { it.id }) { tag ->
                        val isSelected = tag.id in selectedTagIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleTag(tag.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val dotColor = TagColors.getOrElse(tag.colorIndex) { TagColors[0] }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                tag.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
fun TagChip(
    tag: Tag,
    onRemove: () -> Unit,
) {
    val chipColor = TagColors.getOrElse(tag.colorIndex) { TagColors[0] }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(chipColor.copy(alpha = 0.14f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(chipColor),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            tag.name,
            style = MaterialTheme.typography.labelMedium,
            color = chipColor,
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(18.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove ${tag.name}",
                modifier = Modifier.size(14.dp),
                tint = chipColor,
            )
        }
    }
}
