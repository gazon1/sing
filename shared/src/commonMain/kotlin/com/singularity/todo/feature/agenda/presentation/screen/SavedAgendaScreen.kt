package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.presentation.components.ReorderableSectionList
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.nav.PreviewAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.Draft
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewState
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

    // Dialog state: null = no dialog, else the active sheet
    var activeDialog by remember { mutableStateOf<ActiveDialog?>(null) }

    // Auto-pop to previous screen when entering NotFound state
    // NotFound is terminal (no further state transitions), so a simple check is sufficient
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(modeHint) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            val editing = state as? SavedAgendaViewState.Editing
                            if (editing != null && editing.draft.isDirty) {
                                activeDialog = ActiveDialog.ConfirmDiscard
                            } else {
                                navigator.back()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        SavedAgendaContent(
            state = state,
            onIntent = viewModel::onIntent,
            onRequestDelete = { activeDialog = ActiveDialog.ConfirmDelete },
            modifier = Modifier.padding(paddingValues),
        )
    }

    // ConfirmDelete dialog
    if (activeDialog == ActiveDialog.ConfirmDelete) {
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            title = { Text("Delete view?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeDialog = null
                        viewModel.onIntent(SavedAgendaIntent.Delete)
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDialog = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ConfirmDiscard dialog
    if (activeDialog == ActiveDialog.ConfirmDiscard) {
        AlertDialog(
            onDismissRequest = { activeDialog = null },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes that will be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeDialog = null
                        navigator.back()
                    },
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDialog = null }) {
                    Text("Keep editing")
                }
            },
        )
    }
}

/** Active overlay dialog. */
private enum class ActiveDialog {
    ConfirmDelete,
    ConfirmDiscard,
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
            val scrollState = rememberScrollState()

            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(24.dp)
                    .imePadding(),
            ) {
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

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    state.decodeError -> {
                        Text(
                            text = "Unable to decode sections",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    state.draft.sections.isNotEmpty() -> {
                        Text(
                            text = "Sections — drag to reorder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ReorderableSectionList(
                            sections = state.draft.sections,
                            onSectionsReordered = { reordered ->
                                onIntent(SavedAgendaIntent.SectionsReordered(reordered))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        Text(
                            text = "No sections",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

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

// ===== Previews =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaContentLoadingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaContent(
            state = SavedAgendaViewState.Loading,
            onIntent = {},
            onRequestDelete = {},
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
                    userId = "u1",
                    name = "My Work Setup",
                    sectionsJson = """{"sections":[{"type":"tasks"},{"type":"notes"}]}""",
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
                sectionCount = 2,
                isSaving = false,
                decodeError = false,
            ),
            onIntent = {},
            onRequestDelete = {},
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
                    userId = "u1",
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
        )
    }
}
