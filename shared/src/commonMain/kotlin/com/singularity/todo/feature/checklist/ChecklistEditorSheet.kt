package com.singularity.todo.feature.checklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistEditorSheet(taskId: String, onDismiss: () -> Unit) {
    val vm: ChecklistEditorViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    LaunchedEffect(Unit) { sheetState.show() }
    LaunchedEffect(taskId) {
        vm.bindToTask(taskId)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Checklist",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Add new item
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.newItemText,
                    onValueChange = { vm.onIntent(ChecklistEditorIntent.NewItemTextChanged(it)) },
                    placeholder = { Text("Add item…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { vm.onIntent(ChecklistEditorIntent.AddItem) },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { vm.onIntent(ChecklistEditorIntent.AddItem) },
                    enabled = state.newItemText.isNotBlank() && !state.adding,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Items list
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.items, key = { it.id.value }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.isCompleted,
                            onCheckedChange = {
                                vm.onIntent(ChecklistEditorIntent.ToggleItem(item.id))
                            },
                        )
                        Text(
                            text = item.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                                color = if (item.isCompleted) {
                                    MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.5f,
                                )
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            ),
                        )
                        IconButton(
                            onClick = { vm.onIntent(ChecklistEditorIntent.DeleteItem(item.id)) },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }

            if (state.items.isEmpty() && state.newItemText.isBlank()) {
                Text(
                    text = "No items yet. Add one above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
