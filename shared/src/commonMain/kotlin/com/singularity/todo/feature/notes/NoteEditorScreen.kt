package com.singularity.todo.feature.notes

import com.singularity.todo.core.ui.preview.PreviewThemed
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.notes.components.EditorBody
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: String?,
    onBack: () -> Unit,
    viewModel: NotesViewModel = koinViewModel(),
) {
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()

    LaunchedEffect(noteId) {
        if (noteId != null) viewModel.openEditor(noteId) else viewModel.createNote()
    }

    NoteEditorScreenContent(
        editorState = editorState,
        onTitleChange = viewModel::editTitle,
        onBodyChange = viewModel::editBody,
        onSaveNow = viewModel::saveNow,
        onBack = {
            viewModel.closeEditor()
            onBack()
        },
        onAiClick = viewModel::improveNote,
    )

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        onNavigateBack = onBack,
        modifier = Modifier.testTag("note_editor_notification_host"),
    )
}

private fun NotesUiEvent.toNotification(): Notification = when (this) {
    is NotesUiEvent.AiResult -> Notification.Text(title = "AI Result", text = text)
    is NotesUiEvent.SaveFailed -> Notification.Error(message)
    NotesUiEvent.NavigateBack -> Notification.NavigateBack
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreenContent(
    editorState: EditorState,
    onTitleChange: (id: String, title: String) -> Unit,
    onBodyChange: (id: String, html: String) -> Unit,
    onSaveNow: () -> Unit,
    onBack: () -> Unit,
    onAiClick: () -> Unit,
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
                    if (editorState is EditorState.Editing) {
                        IconButton(onClick = onSaveNow, modifier = Modifier.testTag(TestTags.NOTE_EDITOR_SAVE)) {
                            Icon(Icons.Filled.Check, contentDescription = "Save")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (editorState) {
            EditorState.Empty -> LoadingIndicator(modifier = Modifier.padding(padding))
            is EditorState.Editing -> EditorBody(
                state = editorState,
                onTitleChange = onTitleChange,
                onBodyChange = onBodyChange,
                onAiClick = onAiClick,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NoteEditorScreenEditingPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NoteEditorScreenContent(
        editorState = EditorState.Editing(
            id = "n1",
            title = "Meeting Notes",
            html = "<p>Discussed <b>Q4 goals</b> with the team.</p>",
            isDirty = false,
        ),
        onTitleChange = { _, _ -> },
        onBodyChange = { _, _ -> },
        onSaveNow = {},
        onBack = {},
        onAiClick = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NoteEditorScreenDirtyPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    NoteEditorScreenContent(
        editorState = EditorState.Editing(
            id = "n2",
            title = "Draft",
            html = "<p>Work in progress...</p>",
            isDirty = true,
        ),
        onTitleChange = { _, _ -> },
        onBodyChange = { _, _ -> },
        onSaveNow = {},
        onBack = {},
        onAiClick = {},
    )
}
