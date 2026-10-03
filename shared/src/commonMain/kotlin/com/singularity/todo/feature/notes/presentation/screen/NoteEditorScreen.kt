
package com.singularity.todo.feature.notes.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.singularity.todo.core.ui.DraftUiState
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.rememberOverlayState
import com.singularity.todo.core.ui.detail.SavedIndicator
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.notes.EditorSession
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.LinkResult
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.components.EditorToolbar
import com.singularity.todo.feature.notes.components.InternalLinkPickerSheet
import com.singularity.todo.feature.notes.presentation.components.NoteAiActionSheet
import com.singularity.todo.feature.notes.presentation.nav.LocalNotesNavigator
import com.singularity.todo.feature.notes.presentation.nav.NotesPreviewWrapper
import com.singularity.todo.feature.notes.presentation.viewmodel.NoteEditor
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesEditorIntent
import com.singularity.todo.feature.notes.rememberEditorSession
import com.singularity.todo.feature.notes.urlFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import org.koin.compose.viewmodel.koinViewModel

// ─── Screen entry ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditorScreen(route: NotesRoute.Editor, viewModel: NoteEditor = koinViewModel()) {
    val navigator = LocalNotesNavigator.current
    val editorState by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(route.noteId, route.taskId) {
        when {
            route.noteId != null -> viewModel.onIntent(NotesEditorIntent.OpenNote(route.noteId.value))
            route.taskId != null -> viewModel.createNoteForTask(route.taskId)
            else -> viewModel.onIntent(NotesEditorIntent.CreateNote)
        }
    }

    // Saved-pill animation: on each successful save, show "Saved" for 1.5s.
    var savedVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.events
            .filterIsInstance<NotesUiEvent.SavedPulse>()
            .collect {
                savedVisible = true
                kotlinx.coroutines.delay(com.singularity.todo.core.platform.TimeConstants.AutoSaveDebounceMs)
                savedVisible = false
            }
    }

    var showAiSheet by remember { mutableStateOf(false) }
    NoteEditorScreenContent(
        editorState = editorState,
        onTitleChange = { title -> viewModel.onIntent(NotesEditorIntent.EditTitle(title)) },
        onBodyChange = { html -> viewModel.onIntent(NotesEditorIntent.EditBody(html)) },
        onSaveNow = { viewModel.onIntent(NotesEditorIntent.SaveNow) },
        onBack = {
            viewModel.onIntent(NotesEditorIntent.Close)
            navigator.back()
        },
        onShowAiSheet = { showAiSheet = true },
        savedVisible = savedVisible,
        searchNotesForLink = viewModel::searchNotesForLink,
        searchTasksForLink = viewModel::searchTasksForLink,
        onAiAction = { action -> viewModel.onIntent(NotesEditorIntent.RunAiAction(action)) },
        showAiSheet = showAiSheet,
        onDismissAiSheet = { showAiSheet = false },
    )

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        onNavigateBack = { navigator.back() },
        modifier = Modifier.testTag(TestTags.NOTE_EDITOR_NOTIFICATION_HOST),
    )
}

// ─── Content ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreenContent(
    editorState: DraftUiStateCompat,
    onTitleChange: (title: String) -> Unit,
    onBodyChange: (html: String) -> Unit,
    onSaveNow: () -> Unit,
    onBack: () -> Unit,
    onShowAiSheet: () -> Unit,
    savedVisible: Boolean = false,
    searchNotesForLink: (suspend (String) -> List<LinkResult>)? = null,
    searchTasksForLink: (suspend (String) -> List<LinkResult>)? = null,
    onAiAction: ((com.singularity.todo.feature.notes.NoteAiAction) -> Unit)? = null,
    showAiSheet: Boolean = false,
    onDismissAiSheet: () -> Unit = {},
) {
    val linkOverlay = rememberOverlayState<NoteLinkSheet>()
    var linkUrl by rememberSaveable { mutableStateOf("") }
    val linkQueryFlow = remember { MutableStateFlow("") }

    val draft = editorState.draft
    val session = rememberEditorSession(draft) { _, html -> onBodyChange(html) }

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
                    SavedIndicator(visible = savedVisible)
                    IconButton(onClick = onSaveNow, modifier = Modifier.testTag(TestTags.NOTE_EDITOR_SAVE)) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
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
                        onAiClick = onShowAiSheet,
                        onLinkClick = { linkOverlay.show(NoteLinkSheet.External) },
                        onInternalLinkClick = { linkOverlay.show(NoteLinkSheet.InternalPicker()) },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            session?.let { editorSession ->
                EditorTitleAndBody(
                    session = editorSession,
                    onTitleChange = { newTitle ->
                        editorSession.updateTitleFieldValue(newTitle)
                        onTitleChange(newTitle)
                    },
                )
            }
        }
    }

    if (linkOverlay.sheet == NoteLinkSheet.External) {
        LinkUrlDialog(
            url = linkUrl,
            onUrlChange = { linkUrl = it },
            onConfirm = { url ->
                session?.richTextState?.addLinkToSelection(url = url)
                session?.recordLink(url)
                linkOverlay.dismissAll()
                linkUrl = ""
                session?.dispatchHtml()
            },
            onDismiss = {
                linkOverlay.dismissAll()
                linkUrl = ""
            },
        )
    }

    val internalPicker = linkOverlay.sheet as? NoteLinkSheet.InternalPicker
    if (internalPicker != null) {
        InternalLinkPickerSheet(
            queryFlow = linkQueryFlow,
            onSearch = { q ->
                val notes = searchNotesForLink?.invoke(q) ?: emptyList()
                val tasks = searchTasksForLink?.invoke(q) ?: emptyList()
                notes + tasks
            },
            onSelected = { result ->
                val url = result.kind.urlFor(result.id)
                session?.richTextState?.addLinkToSelection(url = url)
                session?.recordLink(url)
                session?.dispatchHtml()
                linkQueryFlow.value = ""
            },
            onDismiss = {
                linkOverlay.dismissAll()
                linkQueryFlow.value = ""
            },
        )
    }

    if (showAiSheet) {
        NoteAiActionSheet(
            onSelect = { action ->
                onAiAction?.invoke(action)
                onDismissAiSheet()
            },
            onDismiss = onDismissAiSheet,
        )
    }
}

/**
 * Compatibility typealias so [NoteEditorScreenContent] can accept either
 * the legacy [EditorState.Editing] (used by previews) or [DraftUiStateCompat]
 * (the actual runtime type from [NoteEditor.editorState]).
 */
typealias DraftUiStateCompat = com.singularity.todo.core.ui.DraftUiState<EditorState.Editing>

// ─── Title + Body ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTitleAndBody(session: EditorSession, onTitleChange: (String) -> Unit) {
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

// ─── Preview ─────────────────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NoteEditorScreenEditingPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    NotesPreviewWrapper {
        NoteEditorScreenContent(
            editorState = DraftUiStateCompat(
                draft = EditorState.Editing(
                    id = "n1",
                    title = "Meeting Notes",
                    html = "<p>Discussed <b>Q4 goals</b> with the team.</p>",
                    isDirty = false,
                ),
            ),
            onTitleChange = { },
            onBodyChange = { },
            onSaveNow = {},
            onBack = {},
            onShowAiSheet = {},
            savedVisible = false,
            onAiAction = null,
            showAiSheet = false,
            onDismissAiSheet = {},
        )
    }
}

@Preview
@Composable
private fun NoteEditorScreenDirtyPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    NotesPreviewWrapper {
        NoteEditorScreenContent(
            editorState = DraftUiStateCompat(
                draft = EditorState.Editing(
                    id = "n2",
                    title = "Draft",
                    html = "<p>Work in progress...</p>",
                    isDirty = true,
                ),
            ),
            onTitleChange = { },
            onBodyChange = { },
            onSaveNow = {},
            onBack = {},
            onShowAiSheet = {},
            savedVisible = false,
            onAiAction = null,
            showAiSheet = false,
            onDismissAiSheet = {},
        )
    }
}
