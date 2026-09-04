package com.singularity.todo.feature.notes

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.ResultDialog
import com.singularity.todo.core.ui.components.UiEvent
import com.singularity.todo.feature.notes.components.EditorBody
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: String?,
    onBack: () -> Unit,
    viewModel: NotesViewModel = koinInject(),
) {
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()

    LaunchedEffect(noteId) {
        if (noteId != null) viewModel.openEditor(noteId) else viewModel.createNote()
    }

    var dialogText by remember { mutableStateOf<String?>(null) }
    CollectEvents(viewModel.events) { event ->
        val t = when (event) {
            is UiEvent.ShowDialog -> event.text
            is UiEvent.ShowError -> event.message
            UiEvent.NavigateBack -> return@CollectEvents
        }
        dialogText = t
    }

    NoteEditorScreenContent(
        editorState = editorState,
        onTitleChange = viewModel::editTitle,
        onBodyChange = viewModel::editBody,
        onBack = {
            viewModel.closeEditor()
            onBack()
        },
        onAiClick = viewModel::improveNote,
    )

    ResultDialog(title = "AI Result", text = dialogText, onDismiss = { dialogText = null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreenContent(
    editorState: EditorState,
    onTitleChange: (id: String, title: String) -> Unit,
    onBodyChange: (id: String, html: String) -> Unit,
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
            )
        },
    ) { padding ->
        when (editorState) {
            EditorState.Empty -> LoadingIndicator(modifier = Modifier.padding(padding))
            is EditorState.Saving -> LoadingIndicator(modifier = Modifier.padding(padding))
            is EditorState.Error -> Text(
                text = "Error: ${editorState.message}",
                modifier = Modifier.padding(padding),
                color = MaterialTheme.colorScheme.error,
            )
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
