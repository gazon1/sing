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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    selectedTagIds: Set<String> = emptySet(),
    onTagsSelected: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val vm: TagPickerViewModel = koinViewModel { parametersOf(selectedTagIds) }

    val tags by vm.tags.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val isCreating by vm.isCreating.collectAsStateWithLifecycle()
    val newTagName by vm.newTagName.collectAsStateWithLifecycle()

    TagPickerSheetContent(
        vm = vm,
        selected = selected,
        tags = tags,
        isCreating = isCreating,
        newTagName = newTagName,
        onTagsSelected = onTagsSelected,
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TagPickerSheetContent(
    vm: TagPickerViewModel,
    selected: Set<String>,
    tags: List<com.singularity.todo.feature.tags.Tag>,
    isCreating: Boolean,
    newTagName: String,
    onTagsSelected: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

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
                        onClick = { vm.toggleTag(tag.id.value) },
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
                        onValueChange = { vm.setNewTagName(it) },
                        placeholder = { Text("Tag name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (newTagName.isNotBlank()) {
                                    vm.createTags()
                                }
                                focusManager.clearFocus()
                            },
                        ),
                    )
                    TextButton(
                        onClick = {
                            if (newTagName.isNotBlank()) {
                                vm.createTags()
                            }
                            focusManager.clearFocus()
                        },
                    ) {
                        Text("Create")
                    }
                }
            } else {
                TextButton(
                    onClick = { vm.setCreating(true) },
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
