package com.singularity.todo.feature.tags.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionSignature")
@Composable
fun TagsPickerSheet(selectedIds: List<TagId>, onSelect: (List<TagId>) -> Unit, onDismiss: () -> Unit) {
    val tagsRepo: TagsRepository = koinInject()
    val allTags by tagsRepo.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    val selected = remember(selectedIds) { mutableStateListOf<TagId>().apply { addAll(selectedIds) } }

    TaskEditorSheetHost(
        title = "Tags",
        onClose = onDismiss,
    ) {
        if (allTags.isEmpty()) {
            Text(
                text = "No tags yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(allTags, key = { it.id }) { tag ->
                    TagItem(
                        tag = tag,
                        isSelected = selected.contains(tag.id),
                        onToggle = {
                            if (selected.contains(tag.id)) {
                                selected.remove(tag.id)
                            } else {
                                selected.add(tag.id)
                            }
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = { onSelect(selected.toList()) },
            ) {
                Text("Done")
            }
        }
    }
}

@Suppress("FunctionSignature")
@Composable
private fun TagItem(tag: Tag, isSelected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = tag.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (isSelected) {
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
