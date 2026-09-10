package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var selected by remember { mutableStateOf(selectedTagIds) }
    var isCreating by remember { mutableStateOf(false) }
    var newTagName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        val userId: String = settingsRepo.userId.first()
        tags = tagsRepo.watchTags(userId).first()
    }

    TaskEditorSheetHost(
        title = "Select Tags",
        onClose = onDismiss,
        onConfirm = { onTagsSelected(selected); onDismiss() },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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

            Spacer(modifier = Modifier.height(8.dp))

            // Inline create
            if (isCreating) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        placeholder = { Text("Tag name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (newTagName.isNotBlank()) {
                                    scope.launch {
                                        val userId = settingsRepo.userId.first()
                                        val newTags = newTagName
                                            .split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                        for (name in newTags) {
                                            val newTag = Tag(
                                                id = TagId.generate(),
                                                name = name,
                                                color = 0xFF9E9E9E.toInt(),
                                                createdAt = Clock.now(),
                                                updatedAt = Clock.now(),
                                                userId = userId,
                                            )
                                            tagsRepo.create(newTag)
                                            selected = selected + newTag.id.value
                                        }
                                        newTagName = ""
                                        isCreating = false
                                        tags = tagsRepo.watchTags(userId).first()
                                    }
                                }
                                focusManager.clearFocus()
                            },
                        ),
                    )
                    TextButton(
                        onClick = {
                            if (newTagName.isNotBlank()) {
                                scope.launch {
                                    val userId = settingsRepo.userId.first()
                                    val newTags = newTagName
                                        .split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                    for (name in newTags) {
                                        val newTag = Tag(
                                            id = TagId.generate(),
                                            name = name,
                                            color = 0xFF9E9E9E.toInt(),
                                            createdAt = Clock.now(),
                                            updatedAt = Clock.now(),
                                            userId = userId,
                                        )
                                        tagsRepo.create(newTag)
                                        selected = selected + newTag.id.value
                                    }
                                    newTagName = ""
                                    isCreating = false
                                    tags = tagsRepo.watchTags(userId).first()
                                }
                            }
                            focusManager.clearFocus()
                        },
                    ) {
                        Text("Create")
                    }
                }
            } else {
                TextButton(
                    onClick = { isCreating = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Create new tag")
                }
            }
        }
    }
}
