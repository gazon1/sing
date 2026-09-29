package com.singularity.todo.feature.tags.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.singularity.todo.core.ui.components.ContentState
import com.singularity.todo.core.ui.components.ContentStateMapper
import com.singularity.todo.core.ui.components.StatefulContent
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.presentation.viewmodel.TagGroupsUiState

@Composable
fun TagGroupsScreen(state: TagGroupsUiState, onDelete: (TagGroupId) -> Unit, modifier: Modifier = Modifier) {
    StatefulContent(
        state = state.toContentState(),
        emptyTitle = "No tag groups yet",
        modifier = modifier,
    ) { groups, contentModifier ->
        TagGroupList(groups = groups, modifier = contentModifier, onDelete = onDelete)
    }
}

private fun TagGroupsUiState.toContentState(): ContentState<List<TagGroup>> = ContentStateMapper.tagGroups(this)

@Composable
private fun TagGroupList(groups: List<TagGroup>, modifier: Modifier = Modifier, onDelete: (TagGroupId) -> Unit) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.id.value }) { group ->
            TagGroupCard(group = group, onDelete = { onDelete(group.id) })
        }
    }
}

@Composable
private fun TagGroupCard(group: TagGroup, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(group.color)),
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(":${group.name}:", style = MaterialTheme.typography.bodyLarge)
        }
        IconButton(onClick = { showDeleteConfirm = true }) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete tag group",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }

    // Deleting a group detaches it from every tag that referenced it, so a single tap
    // on the row is too small a target for the whole gesture — confirm first.
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete tag group?") },
            text = { Text("Tags in this group will be ungrouped. The tags themselves are kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
