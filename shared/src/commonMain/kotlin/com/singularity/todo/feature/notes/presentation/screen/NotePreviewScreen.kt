@file:Suppress("NoDirectClockSystem")
// Preview fixtures only: the timestamps are sample data for `@Preview`, not
// behaviour. The rule is right about production code and has nothing to say
// about a hard-coded `Instant` in a composable nobody ships.
@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.singularity.todo.feature.notes.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberOverlayState
import com.singularity.todo.core.ui.detail.ConfirmationSheet
import com.singularity.todo.feature.nav.NotesRoute
import com.singularity.todo.feature.notes.LinkSchemes
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.extractPreviewText
import com.singularity.todo.feature.notes.parseLinkUrl
import com.singularity.todo.feature.notes.presentation.nav.LocalNotesNavigator
import com.singularity.todo.feature.notes.presentation.nav.NotesPreviewWrapper
import com.singularity.todo.feature.notes.presentation.viewmodel.NotePreview
import com.singularity.todo.feature.notes.presentation.viewmodel.NotePreviewIntent
import com.singularity.todo.feature.notes.presentation.viewmodel.NotePreviewState
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

// ─── Overlay state ──────────────────────────────────────────────────────────────

private sealed interface NotePreviewSheet {
    data object DeleteConfirm : NotePreviewSheet
    data object Backlinks : NotePreviewSheet
}

// ─── Screen entry ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePreviewScreen(route: NotesRoute.Preview, viewModel: NotePreview = koinViewModel()) {
    val navigator = LocalNotesNavigator.current
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val sheets = rememberOverlayState<NotePreviewSheet>()

    LaunchedEffect(route.noteId) {
        viewModel.onIntent(NotePreviewIntent.Load(route.noteId.value))
    }

    NotePreviewScreenContent(
        state = state,
        onBack = { navigator.back() },
        onEdit = { navigator.openEditor(route.noteId) },
        onDelete = { sheets.show(NotePreviewSheet.DeleteConfirm) },
        onBacklinksClick = { sheets.show(NotePreviewSheet.Backlinks) },
        onNavigateToNote = { navigator.openPreview(it) },
        onNavigateToTask = { navigator.openTask(it) },
    )

    // Delete confirmation sheet
    if (sheets.sheet == NotePreviewSheet.DeleteConfirm) {
        ConfirmationSheet(
            title = "Delete note?",
            message = "This action cannot be undone.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            onConfirm = {
                viewModel.onIntent(NotePreviewIntent.Delete)
                sheets.dismissSheet()
                navigator.back()
            },
            onDismiss = { sheets.dismissSheet() },
        )
    }

    // Backlinks sheet
    val loadedState = state as? NotePreviewState.Loaded
    if (sheets.sheet == NotePreviewSheet.Backlinks && loadedState != null) {
        BacklinksSheet(
            backlinks = loadedState.backlinks,
            onNoteSelected = {
                sheets.dismissSheet()
                navigator.openPreview(it)
            },
            onDismiss = { sheets.dismissSheet() },
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
    onNavigateToNote: (NoteId) -> Unit,
    onNavigateToTask: (TaskId) -> Unit,
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

                    // Body: read-only RichText with internal link interception
                    if (note.bodyHtml.isNullOrBlank()) {
                        Text(
                            text = "No content",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        )
                    } else {
                        NotePreviewBody(
                            html = note.bodyHtml,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            onNavigateToNote = onNavigateToNote,
                            onNavigateToTask = onNavigateToTask,
                        )
                    }

                    Spacer(modifier = Modifier.height(80.dp)) // bottom bar clearance
                }
            }
        }
    }
}

// ─── Meta Chips ───────────────────────────────────────────────────────────────

/**
 * Read-only metadata strip: when the note was last touched, and its size.
 *
 * These are facts about the note, not actions, so they render as [MetaLabel] rather
 * than as a Chip. Chip is a clickable control by definition and Material draws it with
 * a pressed/ripple affordance; using one for inert text tells the user there is
 * something behind it. The old `SuggestionChip(onClick = {})` did exactly that, on all
 * three of these.
 */
@Composable
private fun MetaChipsRow(note: Note, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (note.updatedAt != note.createdAt) {
            MetaLabel(formatRelativeShort(note.updatedAt))
        }
        if (note.wordCount > 0) {
            MetaLabel("${note.wordCount} words")
        }
        if (note.charCount > 0) {
            MetaLabel("${note.charCount} chars")
        }
    }
}

@Composable
private fun MetaLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ─── Backlinks Sheet ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BacklinksSheet(backlinks: List<Note>, onNoteSelected: (NoteId) -> Unit, onDismiss: () -> Unit) {
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
                                modifier = Modifier.fillMaxWidth().clickable { onNoteSelected(note.id) },
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

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun NotePreviewBody(
    html: String,
    modifier: Modifier = Modifier,
    onNavigateToNote: (NoteId) -> Unit,
    onNavigateToTask: (TaskId) -> Unit,
) {
    val richTextState = rememberRichTextState()
    LaunchedEffect(Unit) {
        snapshotFlow { html }
            .debounce(100.milliseconds)
            .collect { richTextState.setHtml(it) }
    }

    val uriHandler = LocalUriHandler.current
    val interceptedUriHandler = remember(uriHandler, onNavigateToNote, onNavigateToTask) {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                val (prefix, id) = parseLinkUrl(uri) ?: run {
                    uriHandler.openUri(uri)
                    return
                }
                when (prefix) {
                    LinkSchemes.NOTE_PREFIX -> onNavigateToNote(NoteId.fromString(id))
                    LinkSchemes.TASK_PREFIX -> onNavigateToTask(TaskId.fromString(id))
                    else -> uriHandler.openUri(uri)
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalUriHandler provides interceptedUriHandler,
    ) {
        RichText(state = richTextState, modifier = modifier)
    }
}

private fun formatRelativeShort(updatedAt: Instant): String {
    val now = Clock.System.now()
    val diffMs = now.toEpochMilliseconds() - updatedAt.toEpochMilliseconds()
    return when {
        diffMs < 60_000 -> "Just now"
        diffMs < TimeConstants.MILLIS_PER_HOUR -> "${diffMs / 60_000}m ago"
        diffMs < TimeConstants.MILLIS_PER_DAY -> "${diffMs / TimeConstants.MILLIS_PER_HOUR}h ago"
        else -> "${diffMs / TimeConstants.MILLIS_PER_DAY}d ago"
    }
}

// ─── Preview ─────────────────────────────────────────────────────────────────

@Preview
@Composable
private fun NotePreviewLoadingPreview() = NotesPreviewWrapper {
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
private fun NotePreviewLoadedPreview() = NotesPreviewWrapper {
    val note = Note(
        id = NoteId.fromString("n1"),
        userId = UserId.anonymous,
        title = "Meeting Notes",
        bodyHtml = "<p>Discussed <b>Q4 goals</b> with the team.</p>",
        wordCount = 8,
        charCount = 47,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
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
