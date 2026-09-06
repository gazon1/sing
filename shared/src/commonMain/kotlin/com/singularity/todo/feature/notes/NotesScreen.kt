package com.singularity.todo.feature.notes

import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.UserId
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ContentState
import com.singularity.todo.core.ui.components.DeleteActionButton
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.ContentStateMapper
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.StatefulContent
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onNavigateToNote: (String) -> Unit,
    onNavigateToCreateNote: () -> Unit,
    viewModel: NotesViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Notes") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateNote, modifier = Modifier.testTag(TestTags.NOTES_FAB)) {
                Icon(Icons.Filled.Create, contentDescription = "Add Note")
            }
        },
    ) { padding ->
        NotesContent(
            state = state,
            modifier = Modifier.padding(padding),
            onNavigateToNote = onNavigateToNote,
            onDelete = viewModel::delete,
        )
    }
}

@Composable
private fun NotesContent(
    state: NotesUiState,
    modifier: Modifier = Modifier,
    onNavigateToNote: (String) -> Unit,
    onDelete: (NoteId) -> Unit,
) {
    StatefulContent(
        state = state.toContentState(),
        emptyTitle = "No notes yet",
        modifier = modifier,
    ) { notes ->
        NoteList(
            notes = notes,
            modifier = modifier,
            onNavigateToNote = onNavigateToNote,
            onDelete = onDelete,
        )
    }
}

private fun NotesUiState.toContentState() =
    ContentStateMapper.notes(this)

@Composable
private fun NoteList(
    notes: List<Note>,
    modifier: Modifier = Modifier,
    onNavigateToNote: (String) -> Unit,
    onDelete: (NoteId) -> Unit,
) {
    LazyColumn(
        modifier = modifier.testTag(TestTags.NOTES_LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(notes, key = { it.id.value }) { note ->
            NoteCard(
                note = note,
                onClick = { onNavigateToNote(note.id.value) },
                onDelete = { onDelete(note.id) },
            )
        }
    }
}

@Composable
fun NoteCard(note: Note, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (note.isFolder)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = note.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            note.bodyMarkdown?.let { body ->
                Text(
                    text = body.take(100),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            DeleteActionButton(onClick = onDelete)
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotesScreenContentPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotesContent(
        state = NotesUiState.Content(
            notes = listOf(
                PreviewSamples.note("n1", "Ideas", "Meeting notes and **brainstorming**"),
                PreviewSamples.note("n2", "Shopping list"),
            ),
        ),
        onNavigateToNote = {},
        onDelete = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NotesScreenEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotesContent(
        state = NotesUiState.Empty(userId = UserId.anonymous),
        onNavigateToNote = {},
        onDelete = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NoteCardPreview() = PreviewThemed(darkTheme = false) {
    NoteCard(
        note = PreviewSamples.note("n1", "Meeting notes", "Discussed **Q4 goals** with the team"),
        onClick = {},
        onDelete = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NoteCardDarkPreview() = PreviewThemed(darkTheme = true) {
    NoteCard(
        note = PreviewSamples.note("n2", "Untitled"),
        onClick = {},
        onDelete = {},
    )
}
