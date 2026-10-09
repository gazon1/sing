package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.presentation.components.SectionEditorCard
import com.singularity.todo.feature.agenda.presentation.nav.PreviewAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.Draft
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewState

/**
 * Content composable for the saved agenda view edit/create screen.
 * Stateless — receives [SavedAgendaViewState] and emits callbacks.
 * Used by [SavedAgendaScreen] (production) and preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedAgendaContent(
    state: SavedAgendaViewState,
    onIntent: (SavedAgendaIntent) -> Unit,
    onRequestDelete: () -> Unit,
    onRequestAddSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        SavedAgendaViewState.Loading -> {
            LoadingIndicator(modifier = modifier.fillMaxSize())
        }

        SavedAgendaViewState.NotFound -> {
            Column(
                modifier = modifier.fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "View not found",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // Results state is handled at the parent SavedAgendaScreen level —
        // this branch exists only to satisfy exhaustiveness and should never be reached.
        is SavedAgendaViewState.Results -> {
            LoadingIndicator(modifier = modifier.fillMaxSize())
        }

        is SavedAgendaViewState.Editing -> {
            SavedAgendaEditor(
                state = state,
                onIntent = onIntent,
                onRequestDelete = onRequestDelete,
                onRequestAddSection = onRequestAddSection,
                modifier = modifier,
            )
        }
    }
}

/**
 * The editor form: name field and section list in one scrolling column, with the
 * Save / Delete row pinned below it.
 *
 * Split out of [SavedAgendaContent] so the state dispatch above reads as a dispatch
 * rather than as one very long function.
 */
@Composable
private fun SavedAgendaEditor(
    state: SavedAgendaViewState.Editing,
    onIntent: (SavedAgendaIntent) -> Unit,
    onRequestDelete: () -> Unit,
    onRequestAddSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        // A single LazyColumn owns scrolling. Nesting a LazyColumn inside a
        // Column(verticalScroll()) would propagate unbounded height constraints
        // and crash at measure time. Header (name field + section header / empty
        // state) are emitted via item {} so the sections list is the only
        // itemsIndexed block; the action row is a sibling below, not an item.
        SavedAgendaEditorList(
            state = state,
            onIntent = onIntent,
            onRequestAddSection = onRequestAddSection,
            modifier = Modifier
                .weight(1f)
                .padding(24.dp)
                .imePadding(),
        )

        SavedAgendaActionRow(
            state = state,
            onSave = { onIntent(SavedAgendaIntent.Save) },
            onRequestDelete = onRequestDelete,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .imePadding(),
        )
    }
}

/**
 * The scrolling half of the editor: name field, then either the decode error, the
 * section list, or the empty state, then enough trailing room that the last section
 * is not flush against the pinned action row.
 */
@Composable
private fun SavedAgendaEditorList(
    state: SavedAgendaViewState.Editing,
    onIntent: (SavedAgendaIntent) -> Unit,
    onRequestAddSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    LazyColumn(
        // The caller's `modifier` carries the `weight(1f)` — it is a ColumnScope
        // extension, so only the Column can apply it. It is what keeps the action
        // row on screen: `fillMaxSize` here would resolve against the whole
        // viewport and push that row off it.
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "name_field") {
            SavedAgendaNameField(
                name = state.draft.name,
                enabled = !state.isSaving,
                onNameChange = { onIntent(SavedAgendaIntent.NameChanged(it)) },
                onDone = { keyboardController?.hide() },
            )
        }

        when {
            state.decodeError -> {
                item(key = "decode_error") {
                    Text(
                        text = "Unable to decode sections",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            state.draft.sections.isNotEmpty() -> {
                item(key = "sections_header") {
                    SavedAgendaSectionsHeader(onAdd = onRequestAddSection)
                }
                itemsIndexed(
                    items = state.draft.sections,
                    key = { index, section -> "${section.name}#${section.order}#$index" },
                ) { index, section ->
                    SectionEditorCard(
                        section = section,
                        index = index,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.draft.sections.lastIndex,
                        onMoveUp = {
                            val moved = state.draft.sections.toMutableList()
                            moved.add(index - 1, moved.removeAt(index))
                            onIntent(SavedAgendaIntent.SectionsReordered(moved))
                        },
                        onMoveDown = {
                            val moved = state.draft.sections.toMutableList()
                            moved.add(index + 1, moved.removeAt(index))
                            onIntent(SavedAgendaIntent.SectionsReordered(moved))
                        },
                        onDelete = { onIntent(SavedAgendaIntent.SectionRemoved(index)) },
                    )
                }
            }

            else -> {
                item(key = "no_sections") {
                    SavedAgendaEmptySections(onAdd = onRequestAddSection)
                }
            }
        }

        item(key = "actions_spacer") {
            // The action row is no longer the last item: it is pinned
            // below the list, so the scrolling area only needs enough
            // trailing room that the final section is not flush against
            // it. Keeping the buttons in the LazyColumn put them below
            // the fold on a 1024x768 window with a full section list —
            // the form's primary action unreachable without scrolling.
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** The view's name field, as a list item. Its trailing spacer is the gap to the header below. */
@Composable
private fun SavedAgendaNameField(
    name: String,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("View name") },
        placeholder = { Text("My saved view") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.SAVED_AGENDA_NAME_INPUT),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        enabled = enabled,
    )
    Spacer(modifier = Modifier.height(16.dp))
}

/** The "Sections" heading with its Add action. */
@Composable
private fun SavedAgendaSectionsHeader(
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sections",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add")
        }
    }
}

/** Shown instead of the list when the draft has no sections yet. */
@Composable
private fun SavedAgendaEmptySections(
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No sections",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add first section")
        }
    }
}

/**
 * The editor's Save / Delete row, pinned to the bottom of the viewport.
 *
 * Split out of [SavedAgendaContent] so the scrolling [LazyColumn] and the
 * always-visible action row are siblings rather than the actions being the
 * last item in the scroll — a single nested scrolling container is the one
 * arrangement that measures correctly, and the alternative (sticky header
 * inside the list) is not expressible with `LazyColumn` items.
 */
@Composable
private fun SavedAgendaActionRow(
    state: SavedAgendaViewState.Editing,
    onSave: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Button(
            onClick = onSave,
            enabled = state.canSave && !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.SAVED_AGENDA_SAVE_BUTTON),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(18.dp),
                )
            } else {
                Text("Save")
            }
        }
        // Delete button only shown in Edit mode (view != null)
        if (state.view != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRequestDelete,
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.SAVED_AGENDA_DELETE_BUTTON),
            ) {
                Text("Delete view")
            }
        }
    }
}

@Composable
private fun SavedAgendaContentLoadingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaContent(
            state = SavedAgendaViewState.Loading,
            onIntent = {},
            onRequestDelete = {},
            onRequestAddSection = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaContentEditingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaContent(
            state = SavedAgendaViewState.Editing(
                view = SavedAgendaView(
                    id = SavedAgendaViewId("v1"),
                    userId = UserId("u1"),
                    name = "My Work Setup",
                    sectionsJson = """{"sections":[{"type":"tasks"},{"type":"notes"}]}""",
                    createdAt = kotlin.time.Instant.fromEpochSeconds(1784253600),
                    updatedAt = kotlin.time.Instant.fromEpochSeconds(1785496200),
                ),
                draft = Draft(
                    name = "My Work Setup",
                    sections = listOf(
                        Section("today", "Today", 0, Selector.DateBucket(RelativeBucket.Today)),
                        Section("overdue", "Overdue", 1, Selector.DateBucket(RelativeBucket.Overdue)),
                    ),
                    originalName = "My Work Setup",
                    originalSections = emptyList(),
                    initialized = true,
                ),
                sectionCount = 2,
                isSaving = false,
                decodeError = false,
            ),
            onIntent = {},
            onRequestDelete = {},
            onRequestAddSection = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaContentSavingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaContent(
            state = SavedAgendaViewState.Editing(
                view = SavedAgendaView(
                    id = SavedAgendaViewId("v1"),
                    userId = UserId("u1"),
                    name = "My Work Setup",
                    sectionsJson = """{"sections":[]}""",
                    createdAt = kotlin.time.Instant.fromEpochSeconds(1784253600),
                    updatedAt = kotlin.time.Instant.fromEpochSeconds(1785496200),
                ),
                draft = Draft(
                    name = "My Work Setup",
                    sections = emptyList(),
                    originalName = "My Work Setup",
                    originalSections = emptyList(),
                    initialized = true,
                ),
                sectionCount = 0,
                isSaving = true,
                decodeError = false,
            ),
            onIntent = {},
            onRequestDelete = {},
            onRequestAddSection = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaContentNotFoundPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaContent(
            state = SavedAgendaViewState.NotFound,
            onIntent = {},
            onRequestDelete = {},
            onRequestAddSection = {},
        )
    }
}
