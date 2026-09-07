package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    selectedTagIds: Set<String> = emptySet(),
    onTagsSelected: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tagsRepo: TagsRepository = koinInject()
    val settingsRepo: SettingsRepository = koinInject()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var selected by remember { mutableStateOf(selectedTagIds) }

    LaunchedEffect(Unit) {
        val userId: String = settingsRepo.userId.first()
        tags = tagsRepo.watchTags(userId).first()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Select Tags",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Action buttons
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                TextButton(onClick = { onTagsSelected(selected); onDismiss() }) {
                    Text("Done")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
