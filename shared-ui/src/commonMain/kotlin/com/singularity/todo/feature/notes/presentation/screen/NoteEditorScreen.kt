
package com.singularity.todo.feature.notes.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditorDefaults
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.text.Revisioned
import com.singularity.todo.core.ui.DraftUiState
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.DiscardChangesDialog
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.ImeAwareBackHandler
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.TopAppBar
import com.singularity.todo.core.ui.components.rememberOverlayState
import com.singularity.todo.core.ui.detail.SavedIndicator
import com.singularity.todo.core.ui.detail.rememberSavedPillState
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.notes.EditorSession
import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.LinkResult
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NotesUiEvent
import com.singularity.todo.feature.notes.components.EditorToolbar
import com.singularity.todo.feature.notes.components.InternalLinkPickerSheet
import com.singularity.todo.feature.notes.presentation.components.NoteAiActionSheet
import com.singularity.todo.feature.notes.presentation.nav.LocalNotesNavigator
import com.singularity.todo.feature.notes.presentation.nav.NotesPreviewWrapper
import com.singularity.todo.feature.notes.presentation.viewmodel.NoteEditor
import com.singularity.todo.feature.notes.presentation.viewmodel.NoteProposalsIntent
import com.singularity.todo.feature.notes.presentation.viewmodel.NoteProposalsUiEvent
import com.singularity.todo.feature.notes.presentation.viewmodel.NoteProposalsViewModel
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesEditorIntent
import com.singularity.todo.feature.notes.rememberEditorSession
import com.singularity.todo.feature.notes.urlFor
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.presentation.components.ProposalCardsSection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// ─── Screen entry ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditorScreen(
    route: NotesRoute.Editor,
    viewModel: NoteEditor = koinViewModel(),
) {
    val navigator = LocalNotesNavigator.current
    val editorState by viewModel.stateFlow.collectAsStateWithLifecycle()

    LaunchedEffect(route.noteId, route.taskId) {
        when {
            route.noteId != null -> viewModel.onIntent(NotesEditorIntent.OpenNote(route.noteId.value))
            route.taskId != null -> viewModel.createNoteForTask(route.taskId)
            else -> viewModel.onIntent(NotesEditorIntent.CreateNote)
        }
    }

    // Saved-pill animation: on each successful save, show "Saved" for one autosave window,
    // through the same state holder the task detail uses. The holder cancels the previous
    // timer on a new pulse, so two saves close together keep the pill up for a full window
    // after the later one instead of letting the earlier deadline hide it.
    //
    // The filtered flow is remembered rather than built inline: `filterIsInstance`
    // allocates a new Flow on every call, and the holder keys its collector on the flow
    // it is given. Handing it a fresh instance per recomposition would restart the
    // collector on every keystroke and drop the pulses that land mid-restart — the pill
    // would simply never show up.
    val savedPulses =
        remember(viewModel.events) {
            viewModel.events.filterIsInstance<NotesUiEvent.SavedPulse>()
        }
    val savedPill = rememberSavedPillState(savedPulses)

    // The AI's proposed changes to this note. A note that is being created has none
    // yet, so the ViewModel is asked about an empty id and shows nothing.
    val proposalsViewModel: NoteProposalsViewModel =
        koinViewModel {
            parametersOf(NoteId.fromString(route.noteId?.value.orEmpty()))
        }
    val proposalsState by proposalsViewModel.stateFlow.collectAsStateWithLifecycle()

    var showAiSheet by remember { mutableStateOf(false) }
    // Leaving with text the editor has not stored yet asks first. The draft
    // knows: `isDirty` stands until the autosave has written the text, and
    // a back press inside that window would drop the last keystrokes.
    var showDiscard by remember { mutableStateOf(false) }
    val onBack: () -> Unit = {
        viewModel.onIntent(NotesEditorIntent.Close)
        navigator.back()
    }
    // The header's back button and the system back press mean the same thing —
    // and both of them ask before they throw the text away.
    //
    // The question is not permanent, and "Discard" is not a lie: `isDirty` here is the
    // draft-local flag that `NoteEditor.onAutosaved` clears the moment the debounced
    // autosave writes the text. So the guard asks only while leaving really would drop
    // keystrokes, and once the write has landed a back press goes straight out without
    // claiming there is unsaved work that is in fact on disk. (NoteEditor.override
    // onAutosaved — the base class's `DraftMviViewModel.onAutosaved` documentation is the
    // contract this depends on.)
    val guardedBack: () -> Unit = {
        if (editorState.draft.isDirty) {
            showDiscard = true
        } else {
            onBack()
        }
    }
    // The system back press means what the header's back button means — and
    // with the keyboard open it means "close the keyboard" first.
    ImeAwareBackHandler { guardedBack() }
    NoteEditorScreenContent(
        editorState = editorState,
        noteNotFound = viewModel.noteNotFound,
        onTitleChange = { title -> viewModel.onIntent(NotesEditorIntent.EditTitle(title)) },
        onBodyChange = { html -> viewModel.onIntent(NotesEditorIntent.EditBody(html)) },
        onSaveNow = { viewModel.onIntent(NotesEditorIntent.SaveNow) },
        onBack = guardedBack,
        onShowAiSheet = { showAiSheet = true },
        savedVisible = savedPill.visible,
        searchNotesForLink = viewModel::searchNotesForLink,
        searchTasksForLink = viewModel::searchTasksForLink,
        onAiAction = { action -> viewModel.onIntent(NotesEditorIntent.RunAiAction(action)) },
        showAiSheet = showAiSheet,
        onDismissAiSheet = { showAiSheet = false },
        pendingProposals = proposalsState.proposals,
        onConfirmProposal = { itemId ->
            proposalsViewModel.onIntent(NoteProposalsIntent.Confirm(itemId))
        },
        onRejectProposal = { itemId, reason ->
            proposalsViewModel.onIntent(NoteProposalsIntent.Reject(itemId, reason))
        },
        onConfirmAllProposals = { proposalId ->
            proposalsViewModel.onIntent(NoteProposalsIntent.ConfirmAll(proposalId))
        },
        onDismissProposal = { proposalId ->
            proposalsViewModel.onIntent(NoteProposalsIntent.Dismiss(proposalId))
        },
        onEditProposal = { itemId, text ->
            proposalsViewModel.onIntent(NoteProposalsIntent.Edit(itemId, text))
        },
        proposalFailures = proposalsState.failures,
    )

    // The question the back press asks when the editor still holds text the
    // repository has not seen. "Discard" leaves and drops the text — the same
    // thing `Close` has always done — but the user says so first.
    if (showDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                showDiscard = false
                onBack()
            },
            onKeepEditing = { showDiscard = false },
        )
    }

    // The proposals carry their own failures, on their own bus. They are merged into
    // the editor's notification stream rather than hosted separately: two hosts over
    // one editor would show two snackbars in the same place.
    val notifications =
        remember(viewModel.events, proposalsViewModel.events) {
            merge(
                viewModel.events.map { it.toNotification() },
                proposalsViewModel.events.map { event ->
                    when (event) {
                        is NoteProposalsUiEvent.ShowError -> Notification.Error(event.message)
                    }
                },
            )
        }

    NotificationHost(
        events = notifications,
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
    noteNotFound: Boolean = false,
    savedVisible: Boolean = false,
    searchNotesForLink: (suspend (String) -> List<LinkResult>)? = null,
    searchTasksForLink: (suspend (String) -> List<LinkResult>)? = null,
    onAiAction: ((com.singularity.todo.feature.notes.NoteAiAction) -> Unit)? = null,
    showAiSheet: Boolean = false,
    onDismissAiSheet: () -> Unit = {},
    pendingProposals: ImmutableList<AiProposal> = persistentListOf(),
    onConfirmProposal: (ProposalItemId) -> Unit = {},
    onRejectProposal: (ProposalItemId, String?) -> Unit = { _, _ -> },
    onConfirmAllProposals: (ProposalId) -> Unit = {},
    onDismissProposal: (ProposalId) -> Unit = {},
    onEditProposal: ((ProposalItemId, String) -> Unit)? = null,
    proposalFailures: ImmutableMap<ProposalItemId, String> = persistentMapOf(),
) {
    val linkOverlay = rememberOverlayState<NoteLinkSheet>()

    if (noteNotFound) {
        return EmptyState(title = "Note not found")
    }

    val draft = editorState.draft
    val session = rememberEditorSession(draft) { _, html -> onBodyChange(html) }

    // The field owns the title's text; the draft keeps the domain value. An external
    // title (the AI rewriting it, a sync) comes back through the bridge.
    //
    // The draft hands over the source, and the source says what it is: the editor
    // owns the title, so the draft counts the replacements and the bridge lifts its
    // echo guard for the value that arrives with a new count. Without it the guard
    // treats the rewrite as a lagging upstream and drops it.
    val titleState =
        rememberBridgedTextFieldState(
            source = draft.titleSource,
            onTextChanged = onTitleChange,
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.NOTE_EDITOR_BACK)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    SavedIndicator(
                        visible = savedVisible,
                        modifier = Modifier.testTag(TestTags.NOTE_EDITOR_SAVED_INDICATOR),
                    )
                    IconButton(
                        onClick = {
                            // Flush the editor before the save reads the draft.
                            //
                            // The body lives inside the editor's state object, and the
                            // dispatch to the ViewModel is asynchronous: a save issued in
                            // the same frame as the last keystroke would otherwise persist
                            // the *previous* body — the user typed, pressed save, and the
                            // note came back without the text. The flush is a no-op when
                            // the dispatch has already happened.
                            session.dispatchHtml()
                            onSaveNow()
                        },
                        modifier = Modifier.testTag(TestTags.NOTE_EDITOR_SAVE),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                EditorToolbar(
                    richTextState = session.richTextState,
                    onHtmlChange = { session.dispatchHtml() },
                    onAiClick = onShowAiSheet,
                    onLinkClick = { linkOverlay.show(NoteLinkSheet.External) },
                    onInternalLinkClick = { linkOverlay.show(NoteLinkSheet.InternalPicker()) },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // The changes the AI proposed for this note, waiting for the user's word.
            ProposalCardsSection(
                proposals = pendingProposals,
                onConfirm = onConfirmProposal,
                onReject = onRejectProposal,
                onConfirmAll = { proposalId -> onConfirmAllProposals(proposalId) },
                onDismiss = { proposalId -> onDismissProposal(proposalId) },
                onEdit = onEditProposal,
                failures = proposalFailures,
            )
            EditorTitleAndBody(
                session = session,
                titleState = titleState,
            )
        }
    }

    if (linkOverlay.sheet == NoteLinkSheet.External) {
        LinkUrlDialog(
            onConfirm = { url ->
                // The link text is the address: there is no selection to speak for it,
                // and the address is what the user typed.
                session.insertLink(url = url, text = url)
                linkOverlay.dismissAll()
            },
            onDismiss = {
                linkOverlay.dismissAll()
            },
        )
    }

    val internalPicker = linkOverlay.sheet as? NoteLinkSheet.InternalPicker
    if (internalPicker != null) {
        InternalLinkPickerSheet(
            onSearch = { q ->
                val notes = searchNotesForLink?.invoke(q) ?: emptyList()
                val tasks = searchTasksForLink?.invoke(q) ?: emptyList()
                notes + tasks
            },
            onSelected = { result ->
                // The title is what the link shows. The sheet offers a note or a task
                // by its title, and a title it shows is a title the link can carry.
                session.insertLink(
                    url = result.kind.urlFor(result.id),
                    text = result.title.ifBlank { "(Untitled)" },
                )
            },
            onDismiss = {
                linkOverlay.dismissAll()
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
private fun EditorTitleAndBody(
    session: EditorSession,
    titleState: TextFieldState,
) {
    val richTextState = session.richTextState

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            state = titleState,
            placeholder = { Text("Title") },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(TestTags.NOTE_EDITOR_TITLE_INPUT),
            lineLimits = TextFieldLineLimits.SingleLine,
        )

        RichTextEditor(
            state = richTextState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .testTag(TestTags.NOTE_EDITOR_BODY),
            colors =
                RichTextEditorDefaults.richTextEditorColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ),
            placeholder = { Text("Start writing...") },
        )
    }
}

// ─── Preview ─────────────────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun NoteEditorScreenEditingPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        NotesPreviewWrapper {
            NoteEditorScreenContent(
                editorState =
                    DraftUiStateCompat(
                        draft =
                            EditorState.Editing(
                                id = "n1",
                                title = Revisioned.initial("Meeting Notes"),
                                html = Revisioned.initial("<p>Discussed <b>Q4 goals</b> with the team.</p>"),
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
private fun NoteEditorScreenDirtyPreview() =
    PreviewThemed(darkTheme = true, useSurface = false) {
        NotesPreviewWrapper {
            NoteEditorScreenContent(
                editorState =
                    DraftUiStateCompat(
                        draft =
                            EditorState.Editing(
                                id = "n2",
                                title = Revisioned.initial("Draft"),
                                html = Revisioned.initial("<p>Work in progress...</p>"),
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
