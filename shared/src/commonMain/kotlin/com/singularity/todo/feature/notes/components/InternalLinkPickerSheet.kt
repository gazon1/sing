package com.singularity.todo.feature.notes.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Task
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.nav.Search
import com.singularity.todo.feature.notes.LinkKind
import com.singularity.todo.feature.notes.LinkResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.time.Duration.Companion.milliseconds

/**
 * Generic Obsidian-style [[Note]] / [[Task]] link picker.
 *
 * The sheet is intentionally decoupled from Note and Task domain types.
 * Callers provide a search function that returns [LinkResult] items — the
 * sheet only renders them. This enables reuse across features and keeps
 * `core/ui/components/` free of feature-domain imports.
 *
 * Usage in NoteEditorScreen:
 * ```kotlin
 * val queryFlow = remember { MutableStateFlow("") }
 * InternalLinkPickerSheet(
 *     queryFlow = queryFlow,
 *     onSearch = { q ->
 *         val notes = noteRepo.searchNotes(q).first()
 *         val tasks = taskRepo.searchTasks(q).first()
 *         notes.map { LinkResult(it.id.value, it.title, LinkKind.Note) } +
 *         tasks.map { LinkResult(it.id.value, it.title, LinkKind.Task) }
 *     },
 *     onSelected = { r -> session.richTextState.addLinkToSelection(urlFor(r)) },
 *     onDismiss = { internalLinkPickerVisible = false },
 * )
 * ```
 *
 * @param queryFlow Flow of the current search query. The sheet writes to it
 *                  via an OutlinedTextField; callers can also push initial state.
 * @param onSearch Called when the query debounces. Returns merged results.
 * @param onSelected Called with the chosen [LinkResult]. Caller handles URL
 *                   insertion and sheet dismissal.
 * @param onDismiss Called when the user dismisses the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun InternalLinkPickerSheet(
    queryFlow: kotlinx.coroutines.flow.MutableStateFlow<String>,
    onSearch: suspend (String) -> List<LinkResult>,
    onSelected: (LinkResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Expanded)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Search field
            val focusManager = LocalFocusManager.current
            OutlinedTextField(
                value = queryFlow.collectAsStateWithLifecycle().value,
                onValueChange = { queryFlow.value = it },
                placeholder = { Text("Search notes and tasks...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(8.dp))

            // Results
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
            ) {
                var isLoading by remember { mutableStateOf(false) }
                var shownResults by remember { mutableStateOf<List<LinkResult>>(emptyList()) }

                // Debounced search
                LaunchedEffect(queryFlow) {
                    queryFlow
                        .debounce(300.milliseconds)
                        .distinctUntilChanged()
                        .filter { it.isNotBlank() }
                        .collect { q ->
                            isLoading = true
                            shownResults = onSearch(q)
                            isLoading = false
                        }
                }

                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    queryFlow.collectAsStateWithLifecycle().value.isBlank() -> {
                        Text(
                            "Type to search notes and tasks",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                    }

                    shownResults.isEmpty() -> {
                        Text(
                            "No results found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                    }

                    else -> {
                        LazyColumn {
                            items(shownResults, key = { "${it.kind}_${it.id}" }) { result ->
                                LinkResultItem(result = result, onClick = {
                                    onSelected(result)
                                    onDismiss()
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkResultItem(result: LinkResult, onClick: () -> Unit) {
    val icon = when (result.kind) {
        LinkKind.Note -> Icons.AutoMirrored.Filled.Note
        LinkKind.Task -> Icons.Default.Task
    }
    androidx.compose.material3.ListItem(
        headlineContent = {
            Text(
                text = result.title.ifBlank { "(Untitled)" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = when (result.kind) {
                    LinkKind.Note -> "Note"
                    LinkKind.Task -> "Task"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
