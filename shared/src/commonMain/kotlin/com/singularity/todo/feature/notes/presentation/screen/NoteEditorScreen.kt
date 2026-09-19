package com.singularity.todo.feature.notes.presentation.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.notes.EditorSession
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.LinkKind
import com.singularity.todo.feature.notes.LinkResult
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.components.EditorToolbar
import com.singularity.todo.feature.notes.components.InternalLinkPickerSheet
import com.singularity.todo.feature.notes.presentation.nav.LocalNotesNavigator
import com.singularity.todo.feature.notes.presentation.nav.NotesPreviewWrapper
import com.singularity.todo.feature.notes.presentation.nav.NotesRoute
import com.singularity.todo.feature.notes.presentation.viewmodel.NoteEditor
import com.singularity.todo.feature.notes.rememberEditorSession
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.milliseconds

// ─── Screen entry ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(route: NotesRoute.Editor, viewModel: NoteEditor = koinViewModel()) {
    val navigator = LocalNotesNavigator.current
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()

    LaunchedEffect(route.noteId) {
        if (route.noteId != null) {
            viewModel.openEditor(route.noteId.value)
        } else {
            viewModel.createNote()
        }
    }

    // Saved-pill animation: on each successful save, show "Saved" for 1.5s.
    var savedVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.savedPulse.collect {
            savedVisible = true
            kotlinx.coroutines.delay(1500.milliseconds)
            savedVisible = false
        }
    }

    NoteEditorScreenContent(
        editorState = editorState,
        onTitleChange = viewModel::editTitle,
        onBodyChange = viewModel::editBody,
        onSaveNow = viewModel::saveNow,
        onBack = {
            viewModel.closeEditor()
            navigator.back()
        },
        onAiClick = viewModel::improveNote,
        onNavigateToNote = { id -> navigator.openPreview(NoteId.fromString(id)) },
        onNavigateToTask = { id -> navigator.openTask(TaskId.fromString(id)) },
        savedVisible = savedVisible,
        searchNotesForLink = viewModel::searchNotesForLink,
        searchTasksForLink = viewModel::searchTasksForLink,
    )

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        onNavigateBack = { navigator.back() },
        modifier = Modifier.testTag("note_editor_notification_host"),
    )
}

private fun NotesUiEvent.toNotification(): Notification = when (this) {
    is NotesUiEvent.AiResult -> Notification.Text(title = "AI Result", text = text)
    is NotesUiEvent.SaveFailed -> Notification.Error(message)
    is NotesUiEvent.Error -> Notification.Error(message)
    NotesUiEvent.NavigateBack -> Notification.None
    NotesUiEvent.SavedPulse -> Notification.None
}

// ─── Content ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreenContent(
    editorState: EditorState,
    onTitleChange: (id: String, title: String) -> Unit,
    onBodyChange: (id: String, html: String) -> Unit,
    onSaveNow: () -> Unit,
    onBack: () -> Unit,
    onAiClick: () -> Unit,
    onNavigateToNote: (String) -> Unit = {},
    onNavigateToTask: (String) -> Unit = {},
    savedVisible: Boolean = false,
    searchNotesForLink: (suspend (String) -> List<LinkResult>)? = null,
    searchTasksForLink: (suspend (String) -> List<LinkResult>)? = null,
) {
    val savedAlpha by animateFloatAsState(
        targetValue = if (savedVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "savedAlpha",
    )

    var linkDialogVisible by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }
    var internalLinkPickerVisible by remember { mutableStateOf(false) }
    val linkQueryFlow = remember { MutableStateFlow("") }

    val session = (editorState as? EditorState.Editing)?.let { editing ->
        rememberEditorSession(editing, onBodyChange)
    }

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
                        if (savedVisible || savedAlpha > 0f) {
                            Text(
                                text = "Saved",
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .alpha(savedAlpha),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        IconButton(onClick = onSaveNow, modifier = Modifier.testTag(TestTags.NOTE_EDITOR_SAVE)) {
                            Icon(Icons.Filled.Check, contentDescription = "Save")
                        }
                    }
                },
            )
        },
        bottomBar = {
            session?.let { editorSession ->
                Column {
                    EditorToolbar(
                        richTextState = editorSession.richTextState,
                        onHtmlChange = { editorSession.dispatchHtml() },
                        onAiClick = onAiClick,
                        onLinkClick = { linkDialogVisible = true },
                        onInternalLinkClick = { internalLinkPickerVisible = true },
                    )
                }
            }
        },
    ) { padding ->
        when (editorState) {
            EditorState.Empty -> {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {}
            }

            is EditorState.Editing -> {
                session?.let { editorSession ->
                    Column(modifier = Modifier.padding(padding)) {
                        EditorTitleAndBody(
                            session = editorSession,
                            onTitleChange = { newTitle ->
                                editorSession.titleFieldValue = newTitle
                                onTitleChange(editorState.id, newTitle)
                            },
                            onNavigateToNote = onNavigateToNote,
                            onNavigateToTask = onNavigateToTask,
                        )
                    }
                }
            }
        }
    }

    if (linkDialogVisible) {
        LinkUrlDialog(
            url = linkUrl,
            onUrlChange = { linkUrl = it },
            onConfirm = { url ->
                session?.richTextState?.addLinkToSelection(url = url)
                session?.recordLink(url)
                linkDialogVisible = false
                linkUrl = ""
                session?.dispatchHtml()
            },
            onDismiss = {
                linkDialogVisible = false
                linkUrl = ""
            },
        )
    }

    if (internalLinkPickerVisible) {
        InternalLinkPickerSheet(
            queryFlow = linkQueryFlow,
            onSearch = { q ->
                val notes = searchNotesForLink?.invoke(q) ?: emptyList()
                val tasks = searchTasksForLink?.invoke(q) ?: emptyList()
                notes + tasks
            },
            onSelected = { result ->
                val url = when (result.kind) {
                    LinkKind.Note -> "note://${result.id}"
                    LinkKind.Task -> "task://${result.id}"
                }
                session?.richTextState?.addLinkToSelection(url = url)
                session?.recordLink(url)
                session?.dispatchHtml()
                linkQueryFlow.value = ""
            },
            onDismiss = {
                internalLinkPickerVisible = false
                linkQueryFlow.value = ""
            },
        )
    }
}

// ─── Title + Body ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTitleAndBody(
    session: EditorSession,
    onTitleChange: (String) -> Unit,
    onNavigateToNote: (String) -> Unit,
    onNavigateToTask: (String) -> Unit,
) {
    val richTextState = session.richTextState

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = session.titleFieldValue,
            onValueChange = onTitleChange,
            placeholder = { Text("Title") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(TestTags.NOTE_EDITOR_TITLE_INPUT),
            singleLine = true,
        )

        RichTextEditor(
            state = richTextState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .testTag(TestTags.NOTE_EDITOR_BODY),
            colors = RichTextEditorDefaults.richTextEditorColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
            placeholder = { Text("Start writing...") },
        )
    }
}

// ─── Link URL Dialog ──────────────────────────────────────────────────────────

@Composable
private fun LinkUrlDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Link") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                label = { Text("URL") },
                placeholder = { Text("https://example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (url.isNotBlank()) onConfirm(url.trim())
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    if (url.isNotBlank()) onConfirm(url.trim())
                },
                enabled = url.isNotBlank(),
            ) {
                Text("Insert")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                focusManager.clearFocus()
                onDismiss()
            }) {
                Text("Cancel")
            }
        },
    )
}

// ─── Preview ─────────────────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NoteEditorScreenEditingPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotesPreviewWrapper {
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
            onNavigateToNote = {},
            onNavigateToTask = {},
            savedVisible = false,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NoteEditorScreenDirtyPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    NotesPreviewWrapper {
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
            onNavigateToNote = {},
            onNavigateToTask = {},
            savedVisible = false,
        )
    }
}
