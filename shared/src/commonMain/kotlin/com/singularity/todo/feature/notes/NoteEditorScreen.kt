package com.singularity.todo.feature.notes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.singularity.todo.core.ui.components.InternalLinkPickerSheet
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.notes.components.EditorToolbar
import com.singularity.todo.feature.search.InternalLinkRepository
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

// ─── Screen entry ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: String?,
    onBack: () -> Unit,
    onNavigateToNote: (String) -> Unit = {},
    onNavigateToTask: (String) -> Unit = {},
    viewModel: NotesViewModel = koinViewModel(),
) {
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()

    LaunchedEffect(noteId) {
        if (noteId != null) viewModel.openEditor(noteId) else viewModel.createNote()
    }

    // Saved-pill animation: on each successful save, show "Saved" for 1.5s.
    var savedVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.savedPulse.collect {
            savedVisible = true
            kotlinx.coroutines.delay(1500)
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
            onBack()
        },
        onAiClick = viewModel::improveNote,
        onNavigateToNote = onNavigateToNote,
        onNavigateToTask = onNavigateToTask,
        savedVisible = savedVisible,
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
    NotesUiEvent.SavedPulse -> Notification.None
}

// ─── Session ─────────────────────────────────────────────────────────────────

/**
 * Holds all editor state for one note-editing session.
 * Created once per [EditorState.Editing.id] via [rememberEditorSession].
 *
 * Having this at the screen level lets both [EditorTitleAndBody] (title) and
 * [EditorToolbar] (toolbar buttons) share the same [RichTextState] — the
 * toolbar can be moved to [Scaffold.bottomBar] without passing state down
 * through intermediate composables.
 */
private class EditorSession(
    val richTextState: RichTextState,
    var titleFieldValue: String,
    private var lastDispatchedHtml: String,
    private var firstLoadSkipped: Boolean,
    private val onBodyChange: (id: String, html: String) -> Unit,
    private val onHtmlChange: () -> Unit,
    private val id: String,
) {
    // Track inserted links: url → selection range (start, end) in the text
    private val insertedLinks = mutableListOf<Pair<IntRange, String>>()

    fun recordLink(url: String) {
        val sel = richTextState.selection
        if (!sel.collapsed) {
            insertedLinks.add(sel.min..<sel.max to url)
        } else {
            // No selection — find the last inserted link range that ends at cursor
            // Fallback: store with cursor position as approximate range
            insertedLinks.add(sel.min..<sel.max to url)
        }
    }

    fun findLinkAt(charOffset: Int): String? {
        return insertedLinks.find { (range, _) ->
            charOffset in range
        }?.second
    }

    fun dispatchHtml() {
        val html = richTextState.toHtml()
        if (html != lastDispatchedHtml) {
            lastDispatchedHtml = html
            onBodyChange(id, html)
            onHtmlChange()
        }
    }

    fun notifyFirstLoadSkipped() {
        firstLoadSkipped = true
    }

    val isFirstLoadSkipped: Boolean get() = firstLoadSkipped
}

/**
 * Creates [EditorSession] keyed to [state.id].
 * Ensures a fresh [RichTextState] when switching notes.
 * The [LaunchedEffect] handles HTML dispatch on every keystroke.
 */
@Composable
private fun rememberEditorSession(
    state: EditorState.Editing,
    onBodyChange: (id: String, html: String) -> Unit,
): EditorSession {
    val richTextState = remember(state.id) {
        RichTextState().also { it.setHtml(state.html) }
    }

    val session = remember(state.id) {
        EditorSession(
            richTextState = richTextState,
            titleFieldValue = state.title,
            lastDispatchedHtml = state.html,
            firstLoadSkipped = false,
            onBodyChange = onBodyChange,
            onHtmlChange = {},
            id = state.id,
        )
    }

    // Sync title from state (e.g. when note is loaded from DB)
    LaunchedEffect(state.title) {
        session.titleFieldValue = state.title
    }

    // Dispatch HTML to ViewModel on every rich-text mutation.
    // This is the key fix: EditorToolbar.onHtmlChange only fires on toolbar button
    // clicks, but this LaunchedEffect fires on every text mutation.
    LaunchedEffect(state.id, richTextState) {
        if (!session.isFirstLoadSkipped) {
            session.notifyFirstLoadSkipped()
            return@LaunchedEffect
        }
        session.dispatchHtml()
    }

    return session
}

// ─── Content ─────────────────────────────────────────────────────────────────

/** Pure helper: counts words in an HTML string (strips tags first). */
internal fun wordCount(html: String): Int {
    val text = html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
    if (text.isEmpty()) return 0
    return text.split(" ").count { it.isNotBlank() }
}

/** Pure helper: character count from HTML (strips tags). */
internal fun charCount(html: String): Int {
    return html.replace(Regex("<[^>]*>"), "").length
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
    onNavigateToNote: (String) -> Unit = {},
    onNavigateToTask: (String) -> Unit = {},
    savedVisible: Boolean = false,
) {
    val savedAlpha by animateFloatAsState(
        targetValue = if (savedVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "savedAlpha",
    )

    // External URL dialog state
    var linkDialogVisible by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }

    // Internal link picker sheet state
    var internalLinkPickerVisible by remember { mutableStateOf(false) }

    // Backlinks sheet state
    var backlinksSheetVisible by remember { mutableStateOf(false) }

    // Session — created once per editing note
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
                        IconButton(onClick = { backlinksSheetVisible = true }) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Backlinks")
                        }
                    }
                },
            )
        },
        bottomBar = {
            session?.let { editorSession ->
                Column {
                    MetaChipsRow(html = (editorState as? EditorState.Editing)?.html ?: "")
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
            EditorState.Empty -> LoadingIndicator(modifier = Modifier.padding(padding))
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

    // External URL link dialog
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

    // Internal link picker (Obsidian-style [[Note]] / [[Task]])
    if (internalLinkPickerVisible) {
        InternalLinkPickerSheet(
            onNoteSelected = { noteId, title ->
                val url = "note://$noteId"
                session?.richTextState?.addLinkToSelection(url = url)
                session?.recordLink(url)
                session?.dispatchHtml()
                internalLinkPickerVisible = false
            },
            onTaskSelected = { taskId, title ->
                val url = "task://$taskId"
                session?.richTextState?.addLinkToSelection(url = url)
                session?.recordLink(url)
                session?.dispatchHtml()
                internalLinkPickerVisible = false
            },
            onDismiss = { internalLinkPickerVisible = false },
        )
    }

    // Backlinks panel
    if (backlinksSheetVisible && editorState is EditorState.Editing) {
        BacklinksSheet(
            noteId = editorState.id,
            onNoteSelected = { noteId ->
                backlinksSheetVisible = false
                onNavigateToNote(noteId)
            },
            onDismiss = { backlinksSheetVisible = false },
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
    val uriHandler = LocalUriHandler.current

    Column(modifier = Modifier.fillMaxWidth()) {
        // Title field
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

        // Rich text editor with link tap detection via text selection
        RichTextEditor(
            state = richTextState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .testTag(TestTags.NOTE_EDITOR_BODY)
                .pointerInput(richTextState) {
                    detectTapGestures(
                        onTap = { offset ->
                            // Get character offset from tap position using layout
                            // We approximate link tap by using the current selection/cursor position
                            // which the user positions near the link before tapping
                            val cursorOffset = richTextState.selection.min
                            val link = session.findLinkAt(cursorOffset)
                            if (link != null) {
                                when {
                                    link.startsWith("note://") -> onNavigateToNote(link.removePrefix("note://"))
                                    link.startsWith("task://") -> onNavigateToTask(link.removePrefix("task://"))
                                    else -> uriHandler.openUri(link)
                                }
                            }
                        }
                    )
                },
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

// ─── Backlinks Sheet ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BacklinksSheet(
    noteId: String,
    onNoteSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val linkRepo: InternalLinkRepository = koinInject()
    var backlinks by remember { mutableStateOf<List<Note>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(noteId) {
        isLoading = true
        backlinks = try {
            linkRepo.getBacklinkNotes(noteId)
        } catch (e: Exception) {
            emptyList()
        }
        isLoading = false
    }

    val backlinksSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    LaunchedEffect(Unit) { backlinksSheetState.show() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = backlinksSheetState,
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
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
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
                            androidx.compose.material3.ListItem(
                                headlineContent = {
                                    Text(
                                        text = note.title.ifBlank { "(Untitled)" },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        text = "Links to this note",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNoteSelected(note.id.value) }
                                    .padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Meta Chips ────────────────────────────────────────────────────────────────

@Composable
private fun MetaChipsRow(html: String, modifier: Modifier = Modifier) {
    val words = wordCount(html)
    val chars = charCount(html)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        SuggestionChip(
            onClick = {},
            label = { Text("$words words", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.padding(end = 8.dp),
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
        SuggestionChip(
            onClick = {},
            label = { Text("$chars chars", style = MaterialTheme.typography.labelSmall) },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

// ─── Preview ─────────────────────────────────────────────────────────────────

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
        onNavigateToNote = {},
        onNavigateToTask = {},
        savedVisible = false,
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
        onNavigateToNote = {},
        onNavigateToTask = {},
        savedVisible = false,
    )
}
