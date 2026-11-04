package com.singularity.todo.feature.notes.presentation.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * The right-hand pane of the notes screen on a wide window.
 *
 * It shows the same preview the narrow layout pushes as a route, hosted without
 * that route: the selected note is state of the list screen, so the list keeps
 * its place and the two panes cannot disagree about what is open. Closing the
 * pane clears the selection instead of popping a back-stack entry — there is no
 * entry to pop, and popping the one that *is* there would leave the notes graph
 * entirely.
 *
 * @param noteId the selected note, or `null` when nothing is selected yet.
 * @param onClose clears the selection — the pane's "back".
 * @param onEdit opens the editor for [noteId], as the full-screen preview does.
 * @param onSelect replaces the selection, for a wikilink followed inside the pane.
 * @param onNavigateToTask leaves the notes graph for a task a wikilink names.
 */
@Composable
internal fun NotesDetailPane(
    noteId: NoteId?,
    onClose: () -> Unit,
    onEdit: (NoteId) -> Unit,
    onSelect: (NoteId) -> Unit,
    onNavigateToTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (noteId == null) {
        EmptyState(
            title = "No note selected",
            subtitle = "Pick a note from the list to read it here",
            testTag = TestTags.NOTES_DETAIL_EMPTY,
            modifier = modifier,
        )
        return
    }

    NotePreviewHost(
        noteId = noteId,
        onBack = onClose,
        onEdit = { onEdit(noteId) },
        onNavigateToNote = onSelect,
        onNavigateToTask = onNavigateToTask,
        modifier = modifier,
    )
}
