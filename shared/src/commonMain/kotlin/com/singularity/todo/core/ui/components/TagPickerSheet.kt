package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPickerSheet(
    selectedTagIds: Set<String> = emptySet(),
    onTagsSelected: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tagsRepo: TagsRepository = koinInject()
    val settingsRepo: SettingsRepository = koinInject()

    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var selected by remember { mutableStateOf(selectedTagIds) }

    LaunchedEffect(Unit) {
        val userId: String = settingsRepo.userId.first()
        tags = tagsRepo.watchTags(userId).first()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Tags") },
        text = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    FilterChip(
                        selected = selected.contains(tag.id.value),
                        onClick = {
                            selected = if (selected.contains(tag.id.value)) {
                                selected - tag.id.value
                            } else {
                                selected + tag.id.value
                            }
                        },
                        label = { Text(tag.name) },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onTagsSelected(selected); onDismiss() }) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
