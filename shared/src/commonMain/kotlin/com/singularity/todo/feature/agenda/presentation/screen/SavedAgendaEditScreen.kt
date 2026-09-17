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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.BackTopAppBar
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.nav.PreviewAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaEditEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaEditIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaEditState
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaEditViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Root composable for the saved agenda view edit screen.
 * Uses [koinViewModel] to obtain the [SavedAgendaEditViewModel] scoped to this nav entry.
 * Navigation events are handled via [LocalAgendaNavigator] provided by the nav graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedAgendaEditScreen(viewId: SavedAgendaViewId, modifier: Modifier = Modifier) {
    val navigator = LocalAgendaNavigator.current
    val viewModel: SavedAgendaEditViewModel = koinViewModel { parametersOf(viewId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    NotificationHost(
        events = viewModel.events,
        mapper = { event: SavedAgendaEditEvent ->
            when (event) {
                is SavedAgendaEditEvent.SaveSuccess -> Notification.Text("Saved", null)
                is SavedAgendaEditEvent.DeleteSuccess -> Notification.NavigateBack
                is SavedAgendaEditEvent.ShowError -> Notification.Error(event.message)
            }
        },
        onNavigateBack = { navigator.back() },
    )

    BackTopAppBar(
        title = "Edit View",
        onBack = { navigator.back() },
        modifier = modifier,
    ) { paddingValues ->
        SavedAgendaEditContent(
            state = state,
            onIntent = viewModel::onIntent,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

/**
 * Content composable for the saved agenda view edit screen.
 * Stateless — receives [SavedAgendaEditState] and emits callbacks.
 * Used by [SavedAgendaEditScreen] (production) and preview.
 *
 * @param state The current UI state.
 * @param onIntent Called to dispatch a user intent.
 * @param modifier Compose modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedAgendaEditContent(
    state: SavedAgendaEditState,
    onIntent: (SavedAgendaEditIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        SavedAgendaEditState.Loading -> {
            LoadingIndicator(modifier = modifier.fillMaxSize())
        }

        SavedAgendaEditState.NotFound -> {
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

        is SavedAgendaEditState.Editing -> {
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
                    value = state.editableName,
                    onValueChange = { onIntent(SavedAgendaEditIntent.NameChanged(it)) },
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

                if (state.sectionCount != null) {
                    Text(
                        text = "${state.sectionCount} section${if (state.sectionCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Unable to decode sections",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { onIntent(SavedAgendaEditIntent.Save) },
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

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onIntent(SavedAgendaEditIntent.Delete) },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete view")
                }
            }
        }
    }
}

// ===== Previews =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaEditContentLoadingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaEditContent(
            state = SavedAgendaEditState.Loading,
            onIntent = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaEditContentEditingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaEditContent(
            state = SavedAgendaEditState.Editing(
                view = SavedAgendaView(
                    id = SavedAgendaViewId("v1"),
                    userId = "u1",
                    name = "My Work Setup",
                    sectionsJson = """{"sections":[{"type":"tasks"},{"type":"notes"}]}""",
                    createdAt = kotlin.time.Instant.fromEpochSeconds(1784253600),
                    updatedAt = kotlin.time.Instant.fromEpochSeconds(1785496200),
                ),
                editableName = "My Work Setup",
                sectionCount = 2,
                isSaving = false,
            ),
            onIntent = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaEditContentSavingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaEditContent(
            state = SavedAgendaEditState.Editing(
                view = SavedAgendaView(
                    id = SavedAgendaViewId("v1"),
                    userId = "u1",
                    name = "My Work Setup",
                    sectionsJson = """{"sections":[]}""",
                    createdAt = kotlin.time.Instant.fromEpochSeconds(1784253600),
                    updatedAt = kotlin.time.Instant.fromEpochSeconds(1785496200),
                ),
                editableName = "My Work Setup",
                sectionCount = 0,
                isSaving = true,
            ),
            onIntent = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaEditContentNotFoundPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaEditContent(
            state = SavedAgendaEditState.NotFound,
            onIntent = {},
        )
    }
}
