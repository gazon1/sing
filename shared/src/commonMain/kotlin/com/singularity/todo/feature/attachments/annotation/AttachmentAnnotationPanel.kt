package com.singularity.todo.feature.attachments.annotation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.ui.IntentActions
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The annotation entry point for a text attachment: a button that opens the list, and the
 * sheet the list lives in.
 *
 * Rendered over the loaded text by
 * [com.singularity.todo.feature.attachments.viewer.TextViewerScreen]. The ViewModel is
 * obtained per attachment through `parametersOf`, so two attachments open in two nav
 * entries do not share one panel.
 *
 * The create form takes explicit offsets and prefills the quote from them — Compose
 * Multiplatform has no supported way to read a text selection back, so this is the honest
 * shape rather than a faked one. See ADR `2026-10-07-annotation-anchor-model`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentAnnotationPanel(
    attachmentId: AttachmentId,
    documentText: String,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val viewModel: AttachmentAnnotationViewModel = koinViewModel {
        parametersOf(attachmentId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onIntent = IntentActions<AttachmentAnnotationIntent> { viewModel.onIntent(it) }

    // The panel needs the file to tell a stale note from a good one, and the viewer is the
    // only thing that has read it.
    LaunchedEffect(documentText) { onIntent(AttachmentAnnotationIntent.DocumentLoaded(documentText)) }

    // A write that fails and reports nowhere is the defect class this project keeps
    // removing, so the event is surfaced rather than swallowed.
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.events.collect { event ->
            when (event) {
                is AttachmentAnnotationUiEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    FloatingActionButton(
        onClick = { onIntent(AttachmentAnnotationIntent.OpenSheet) },
        modifier = modifier,
    ) {
        Text(text = annotationCountLabel(state))
    }

    val content = state as? AttachmentAnnotationUiState.Content ?: return
    if (!content.sheetOpen) return

    ModalBottomSheet(
        onDismissRequest = { onIntent(AttachmentAnnotationIntent.CloseSheet) },
        sheetState = rememberModalBottomSheetState(),
    ) {
        AnnotationSheetContent(state = content, onIntent = onIntent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotationSheetContent(
    state: AttachmentAnnotationUiState.Content,
    onIntent: IntentActions<AttachmentAnnotationIntent>,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text("Annotations (${state.annotations.size})", style = MaterialTheme.typography.titleMedium)

        val draft = state.draft
        if (draft != null) {
            AnnotationEditorForm(
                draft = draft,
                onIntent = onIntent,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@Column
        }

        Button(
            onClick = {
                // The whole document is the default selection: the user narrows it with the
                // two offset fields rather than being asked to do both at once.
                onIntent(
                    AttachmentAnnotationIntent.OpenEditor(
                        start = 0,
                        end = state.documentText.length,
                        quote = state.documentText,
                    ),
                )
            },
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text("New annotation")
        }

        if (state.annotations.isEmpty()) {
            Text(
                "No annotations yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            items(state.annotations, key = { it.annotation.id.value }) { resolved ->
                AnnotationRow(annotation = resolved, onIntent = onIntent)
            }
        }
    }
}

/** One note: the quoted words, the note itself, and the two things a user can do to it. */
@Composable
private fun AnnotationRow(
    annotation: ResolvedAnnotation,
    onIntent: IntentActions<AttachmentAnnotationIntent>,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\"${annotation.annotation.range.quote.take(80)}\"",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.weight(1f),
            )
            if (annotation.isStale) {
                // A label, not a chip: `AssistChip` needs an onClick, and the only honest
                // handler here is none — the note is already on screen, already editable,
                // already deletable. A chip that swallowed the tap would look actionable
                // and do nothing.
                Text(
                    text = "text changed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = annotation.annotation.note.ifBlank { "(no note)" },
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onIntent(AttachmentAnnotationIntent.EditAnnotation(annotation.annotation.id)) },
            ) {
                Text("Edit")
            }
            TextButton(
                onClick = { onIntent(AttachmentAnnotationIntent.Delete(annotation.annotation.id)) },
            ) {
                Text("Delete")
            }
        }
    }
}

/**
 * The create/edit form.
 *
 * The offset fields are editable only when creating: re-anchoring an existing note is a
 * different operation from editing its words, and letting one form do both would let a note
 * edit silently move what the note points at.
 */
@Composable
private fun AnnotationEditorForm(
    draft: AnnotationDraft,
    onIntent: IntentActions<AttachmentAnnotationIntent>,
    modifier: Modifier = Modifier,
) {
    val isEditing = draft.editing != null
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (isEditing) "Edit annotation" else "New annotation",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.start,
                onValueChange = { onIntent(AttachmentAnnotationIntent.DraftStartChanged(it)) },
                label = { Text("Start") },
                singleLine = true,
                enabled = !isEditing,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = draft.end,
                onValueChange = { onIntent(AttachmentAnnotationIntent.DraftEndChanged(it)) },
                label = { Text("End") },
                singleLine = true,
                enabled = !isEditing,
                modifier = Modifier.weight(1f),
            )
        }
        if (!isEditing) {
            OutlinedTextField(
                value = draft.quote,
                onValueChange = { onIntent(AttachmentAnnotationIntent.DraftQuoteChanged(it)) },
                label = { Text("Selected text") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = draft.note,
            onValueChange = { onIntent(AttachmentAnnotationIntent.DraftNoteChanged(it)) },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onIntent(AttachmentAnnotationIntent.SubmitEditor) }) {
                Text("Save")
            }
            TextButton(onClick = { onIntent(AttachmentAnnotationIntent.CloseEditor) }) {
                Text("Cancel")
            }
        }
    }
}

/**
 * The button label doubles as the list's summary, so the count is visible before the sheet
 * opens — a button that says "Notes" tells a user with three notes nothing.
 */
private fun annotationCountLabel(state: AttachmentAnnotationUiState): String {
    val count = (state as? AttachmentAnnotationUiState.Content)?.annotations?.size ?: return "…"
    return if (count == 0) "Notes" else "Notes ($count)"
}
