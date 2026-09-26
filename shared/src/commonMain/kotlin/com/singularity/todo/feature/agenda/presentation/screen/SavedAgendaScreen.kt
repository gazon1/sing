package com.singularity.todo.feature.agenda.presentation.screen

import com.singularity.todo.core.ids.UserId
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.BackTopAppBar
import com.singularity.todo.core.ui.components.ConfirmActionDialog
import com.singularity.todo.core.ui.components.DiscardChangesDialog
import com.singularity.todo.core.ui.components.ListPickerSheet
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.typeDescription
import com.singularity.todo.feature.agenda.presentation.components.SectionEditorCard
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.nav.PreviewAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.Draft
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewState
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Root composable for the saved agenda view edit/create screen.
 * Uses [koinViewModel] to obtain the [SavedAgendaViewModel] scoped to this nav entry.
 *
 * @param viewId The ID of the view to edit. Pass null when creating a new view.
 * @param seed The [AgendaDefinition] to seed a new view from. Pass null when editing.
 * @param modeHint Informational label shown in the top bar ("Edit View" or "Create View").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedAgendaScreen(
    viewId: SavedAgendaViewId?,
    seed: AgendaDefinition?,
    modeHint: String,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalAgendaNavigator.current

    val mode: SavedAgendaScreenMode = if (viewId != null) {
        SavedAgendaScreenMode.Edit(viewId)
    } else {
        SavedAgendaScreenMode.Create(seed!!)
    }

    val viewModel: SavedAgendaViewModel = koinViewModel { parametersOf(mode) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Dialog state: null = no dialog/sheet, else the active dialog
    val dialogs = rememberDialogState<ActiveDialog>()

    // Auto-pop to previous screen when entering NotFound state
    LaunchedEffect(state) {
        if (state is SavedAgendaViewState.NotFound) {
            navigator.back()
        }
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { event: SavedAgendaEvent ->
            when (event) {
                is SavedAgendaEvent.SaveSuccess -> Notification.Text("Saved", null)
                is SavedAgendaEvent.DeleteSuccess -> Notification.NavigateBack
                is SavedAgendaEvent.ShowError -> Notification.Error(event.message)
            }
        },
        onNavigateBack = { navigator.back() },
    )

    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    BackTopAppBar(
        title = modeHint,
        onBack = {
            val editing = state as? SavedAgendaViewState.Editing
            if (editing != null && editing.draft.isDirty) {
                dialogs.show(ActiveDialog.ConfirmDiscard)
            } else {
                navigator.back()
            }
        },
        modifier = modifier,
    ) { paddingValues ->
        SavedAgendaContent(
            state = state,
            onIntent = viewModel::onIntent,
            onRequestDelete = { dialogs.show(ActiveDialog.ConfirmDelete) },
            onRequestAddSection = { dialogs.show(ActiveDialog.AddSection) },
            modifier = Modifier.padding(paddingValues),
        )
    }

    // ConfirmDelete dialog
    if (dialogs.active == ActiveDialog.ConfirmDelete) {
        ConfirmActionDialog(
            title = "Delete view?",
            text = "This action cannot be undone.",
            confirmButtonText = "Delete",
            onConfirm = {
                dialogs.dismiss()
                viewModel.onIntent(SavedAgendaIntent.Delete)
            },
            onDismiss = { dialogs.dismiss() },
        )
    }

    // ConfirmDiscard dialog
    if (dialogs.active == ActiveDialog.ConfirmDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                dialogs.dismiss()
                navigator.back()
            },
            onKeepEditing = { dialogs.dismiss() },
        )
    }

    // Add section bottom sheet
    if (dialogs.active == ActiveDialog.AddSection) {
        val nextOrder = (state as? SavedAgendaViewState.Editing)?.draft?.sections?.size ?: 0
        ListPickerSheet(
            title = "Add section",
            onItemSelected = { selector ->
                dialogs.dismiss()
                val section = Section(
                    name = selector.typeDescription,
                    order = nextOrder,
                    selector = selector,
                )
                viewModel.onIntent(SavedAgendaIntent.SectionAdded(section, nextOrder))
            },
            onDismiss = { dialogs.dismiss() },
            sheetState = sheetState,
        ) {
            item("Active tasks", Selector.Statuses(setOf(TaskStatus.Active)))
            item("Completed tasks", Selector.Statuses(setOf(TaskStatus.Completed)))
            item("Due today", Selector.DateBucket(RelativeBucket.Today))
            item("Overdue", Selector.DateBucket(RelativeBucket.Overdue))
            item("No date", Selector.DateBucket(RelativeBucket.NoDate))
            item("This week", Selector.DateBucket(RelativeBucket.ThisWeek))
            item("Next week", Selector.DateBucket(RelativeBucket.NextWeek))
        }
    }
}

/** Active overlay dialog. */
private sealed interface ActiveDialog {
    data object ConfirmDelete : ActiveDialog
    data object ConfirmDiscard : ActiveDialog
    data object AddSection : ActiveDialog
}

/**
 * Content composable for the saved agenda view edit/create screen.
 * Stateless — receives [SavedAgendaViewState] and emits callbacks.
 * Used by [SavedAgendaScreen] (production) and preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedAgendaContent(
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
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "View not found",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        is SavedAgendaViewState.Editing -> {
            val keyboardController = LocalSoftwareKeyboardController.current
            val listState = rememberLazyListState()

            // A single LazyColumn owns scrolling. Nesting a LazyColumn inside a
            // Column(verticalScroll()) would propagate unbounded height constraints
            // and crash at measure time. Header (name field + section header / empty
            // state) and footer (save + delete) are emitted via item {} so the
            // sections list is the only itemsIndexed block.
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .imePadding(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "name_field") {
                    OutlinedTextField(
                        value = state.draft.name,
                        onValueChange = { onIntent(SavedAgendaIntent.NameChanged(it)) },
                        label = { Text("View name") },
                        placeholder = { Text("My saved view") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { keyboardController?.hide() },
                        ),
                        enabled = !state.isSaving,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
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
                                TextButton(onClick = onRequestAddSection) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add")
                                }
                            }
                        }
                        itemsIndexed(
                            items = state.draft.sections,
                            key = { index, section -> "${section.name}#${section.order}#$index" },
                        ) { index, section ->
                            SectionEditorCard(
                                section = section,
                                index = index,
                                onDelete = { onIntent(SavedAgendaIntent.SectionRemoved(index)) },
                            )
                        }
                    }

                    else -> {
                        item(key = "no_sections") {
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
                                OutlinedButton(onClick = onRequestAddSection) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add first section")
                                }
                            }
                        }
                    }
                }

                item(key = "actions") {
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { onIntent(SavedAgendaIntent.Save) },
                        enabled = state.canSave && !state.isSaving,
                        modifier = Modifier.fillMaxWidth(),
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
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onRequestDelete,
                            enabled = !state.isSaving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Delete view")
                        }
                    }
                }
            }
        }
    }
}

// ===== Previews =====

@androidx.compose.ui.tooling.preview.Preview
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
                        Section("Today", 0, Selector.DateBucket(RelativeBucket.Today)),
                        Section("Overdue", 1, Selector.DateBucket(RelativeBucket.Overdue)),
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
