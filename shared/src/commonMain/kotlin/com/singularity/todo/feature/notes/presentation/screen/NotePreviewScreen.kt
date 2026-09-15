package com.singularity.todo.feature.notes

// Re-export NotePreviewState so preview functions in this file can reference it
// without a runtime ClassNotFoundException during Compose Preview rendering.
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ids.UserId
import org.koin.compose.viewmodel.koinViewModel

// ─── Screen entry ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePreviewScreen(
    noteId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onNavigateToNote: (String) -> Unit,
    onNavigateToTask: (String) -> Unit,
    viewModel: NotePreview = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var backlinksSheetVisible by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        viewModel.loadNote(noteId)
    }

    NotePreviewScreenContent(
        state = state,
        onBack = onBack,
        onEdit = { onEdit(noteId) },
        onDelete = { deleteDialogVisible = true },
        onBacklinksClick = { backlinksSheetVisible = true },
        onNavigateToNote = onNavigateToNote,
        onNavigateToTask = onNavigateToTask,
    )

    // Delete confirmation dialog
    if (deleteDialogVisible) {
        AlertDialog(
            onDismissRequest = { deleteDialogVisible = false },
            title = { Text("Delete note?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete()
                        deleteDialogVisible = false
                        onBack()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogVisible = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Backlinks panel
    val loadedState = state as? NotePreviewState.Loaded
    if (backlinksSheetVisible && loadedState != null) {
        BacklinksSheet(
            backlinks = loadedState.backlinks,
            onNoteSelected = { id ->
                backlinksSheetVisible = false
                onNavigateToNote(id)
            },
            onDismiss = { backlinksSheetVisible = false },
        )
    }
}

// ─── Content ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalRichTextApi::class)
@Composable
fun NotePreviewScreenContent(
    state: NotePreviewState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBacklinksClick: () -> Unit,
    onNavigateToNote: (String) -> Unit,
    onNavigateToTask: (String) -> Unit,
) {

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val loaded = state as? NotePreviewState.Loaded
                    if (loaded != null) {
                        IconButton(
                            onClick = onBacklinksClick,
                            modifier = Modifier.testTag(TestTags.NOTES_BACKLINKS_BUTTON),
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null)
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Edit")
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    ) { padding ->
        when (state) {
            NotePreviewState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            is NotePreviewState.Loaded -> {
                val note = state.note
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Hero: title
                    Text(
                        text = note.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    // Meta chips
                    MetaChipsRow(note = note, modifier = Modifier.padding(horizontal = 16.dp))

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Body: read-only RichText
                    if (note.bodyHtml.isNullOrBlank()) {
                        Text(
                            text = "No content",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    } else {
                        val richTextState = remember(note.id) {
                            RichTextState().also { it.setHtml(note.bodyHtml) }
                        }

                        RichText(
                            state = richTextState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            // Handle note:// and task:// links manually via uriHandler
                        )
                    }

                    Spacer(modifier = Modifier.height(80.dp)) // bottom bar clearance
                }
            }
        }
    }
}

// ─── Meta Chips ───────────────────────────────────────────────────────────────

@Composable
private fun MetaChipsRow(note: Note, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (note.updatedAt != note.createdAt) {
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        formatRelativeShort(note.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
        if (note.wordCount > 0) {
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        "${note.wordCount} words",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
        if (note.charCount > 0) {
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        "${note.charCount} chars",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
    }
}

// ─── Backlinks Sheet ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BacklinksSheet(
    backlinks: List<Note>,
    onNoteSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Backlinks",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                backlinks.isEmpty() -> {
                    Text(
                        "No backlinks yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }

                else -> {
                    LazyColumn {
                        items(backlinks, key = { it.id.value }) { note ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = note.title.ifBlank { "(Untitled)" },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    val snippet = extractPreviewText(note.bodyMarkdown, 80)
                                    if (snippet.isNotBlank()) {
                                        Text(
                                            text = snippet,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Filled.Link,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun formatRelativeShort(updatedAt: kotlin.time.Instant): String {
    val now = kotlin.time.Clock.System.now()
    val diffMs = now.toEpochMilliseconds() - updatedAt.toEpochMilliseconds()
    return when {
        diffMs < 60_000 -> "Just now"
        diffMs < 3_600_000 -> "${diffMs / 60_000}m ago"
        diffMs < 86_400_000 -> "${diffMs / 3_600_000}h ago"
        else -> "${diffMs / 86_400_000}d ago"
    }
}

// ─── Preview ─────────────────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotePreviewLoadingPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotePreviewScreenContent(
        state = NotePreviewState.Loading,
        onBack = {},
        onEdit = {},
        onDelete = {},
        onBacklinksClick = {},
        onNavigateToNote = {},
        onNavigateToTask = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotePreviewLoadedPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    val note = Note(
        id = NoteId.fromString("n1"),
        userId = UserId.anonymous,
        title = "Meeting Notes",
        bodyHtml = "<p>Discussed <b>Q4 goals</b> with the team.</p>",
        wordCount = 8,
        charCount = 47,
        createdAt = kotlin.time.Clock.System.now(),
        updatedAt = kotlin.time.Clock.System.now(),
    )
    NotePreviewScreenContent(
        state = NotePreviewState.Loaded(
            note = note,
            backlinks = emptyList(),
        ),
        onBack = {},
        onEdit = {},
        onDelete = {},
        onBacklinksClick = {},
        onNavigateToNote = {},
        onNavigateToTask = {},
    )
}
