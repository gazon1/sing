package com.singularity.todo.feature.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ContentStateMapper
import com.singularity.todo.core.ui.components.DeleteActionButton
import com.singularity.todo.core.ui.components.StatefulContent
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tags.components.AddTagDialog

@Composable
fun TagsScreen(
    state: TagsUiState,
    modifier: Modifier = Modifier,
    onCreate: (name: String, color: Int) -> Unit,
    onDelete: (TagId) -> Unit,
    onRename: (id: TagId, name: String, color: Int) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var tagBeingRenamed by remember { mutableStateOf<Tag?>(null) }

    StatefulContent(
        state = state.toContentState(),
        emptyTitle = "No tags yet",
        modifier = modifier,
    ) { tags, contentModifier ->
        Box(modifier = contentModifier) {
            TagList(
                tags = tags,
                onDelete = onDelete,
                onRename = { tag -> tagBeingRenamed = tag },
            )
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .testTag(TestTags.TAGS_FAB)
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add tag")
            }
        }
    }

    if (showAddDialog) {
        AddTagDialog(
            onConfirm = { name, color ->
                onCreate(name, color)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    tagBeingRenamed?.let { tag ->
        AddTagDialog(
            onConfirm = { name, color ->
                onRename(tag.id, name, color)
                tagBeingRenamed = null
            },
            onDismiss = { tagBeingRenamed = null },
            title = "Rename Tag",
            initialName = tag.name,
            initialColor = tag.color,
            confirmLabel = "Save",
        )
    }
}

private fun TagsUiState.toContentState() = ContentStateMapper.tags(this)

@Composable
private fun TagList(tags: List<Tag>, onDelete: (TagId) -> Unit, onRename: (Tag) -> Unit) {
    LazyColumn(
        modifier = Modifier.testTag(TestTags.TAGS_LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tags, key = { it.id.value }) { tag ->
            TagCard(tag = tag, onDelete = { onDelete(tag.id) }, onRename = { onRename(tag) })
        }
    }
}

@Composable
fun TagCard(tag: Tag, onDelete: () -> Unit, onRename: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(tag.color)),
            )
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            IconButton(
                onClick = onRename,
                modifier = Modifier.testTag(TestTags.tagRename(tag.name)),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Rename ${tag.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DeleteActionButton(onClick = onDelete)
        }
    }
}

// ===== Preview =====

@Preview
@Composable
private fun TagsScreenContentPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    TagsScreen(
        state = TagsUiState.Content(
            tags = listOf(
                PreviewSamples.tag("tg1", "work", 0xFFE91E63.toInt()),
                PreviewSamples.tag("tg2", "home", 0xFF2196F3.toInt()),
                PreviewSamples.tag("tg3", "urgent", 0xFFF44336.toInt()),
            ),
        ),
        onCreate = { _, _ -> },
        onDelete = {},
        onRename = { _, _, _ -> },
    )
}

@Preview
@Composable
private fun TagsScreenEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    TagsScreen(
        state = TagsUiState.Empty,
        onCreate = { _, _ -> },
        onDelete = {},
        onRename = { _, _, _ -> },
    )
}

@Preview
@Composable
private fun TagsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    TagsScreen(
        state = TagsUiState.Content(
            tags = listOf(
                PreviewSamples.tag("tg1", "personal", 0xFF9C27B0.toInt()),
            ),
        ),
        onCreate = { _, _ -> },
        onDelete = {},
        onRename = { _, _, _ -> },
    )
}
