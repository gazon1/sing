package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.components.BackTopAppBar
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.presentation.components.SavedAgendaCard
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import kotlin.time.Instant
import com.singularity.todo.feature.agenda.presentation.nav.PreviewAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListDeps
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListState
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListViewModel
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeSavedAgendaViewsRepository
import org.koin.compose.viewmodel.koinViewModel

/**
 * Root composable for the saved agenda views list screen.
 * Uses [koinViewModel] to obtain the [SavedAgendaListViewModel] scoped to this nav entry.
 * Navigation events are handled via [LocalAgendaNavigator] provided by the nav graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedAgendaListScreen(modifier: Modifier = Modifier) {
    val navigator = LocalAgendaNavigator.current
    val viewModel: SavedAgendaListViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Views") },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.Filled.List, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        SavedAgendaListContent(
            state = state,
            onViewSelected = { viewId -> navigator.openSavedAgendaEdit(viewId) },
            onDelete = { viewId -> viewModel.onIntent(SavedAgendaListIntent.Delete(viewId)) },
            modifier = Modifier.padding(paddingValues),
        )
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { e -> when (e) { is SavedAgendaListEvent.ShowError -> Notification.Error(e.message); else -> Notification.None } },
    )
}

/**
 * Content composable for the saved agenda views list screen.
 * Stateless — receives [SavedAgendaListState] and emits callbacks.
 * Used by [SavedAgendaListScreen] (production) and preview.
 *
 * @param state The current UI state.
 * @param onViewSelected Called when the user taps a saved view card.
 * @param onDelete Called when the user taps the delete button on a card.
 * @param modifier Compose modifier.
 */
@Composable
private fun SavedAgendaListContent(
    state: SavedAgendaListState,
    onViewSelected: (SavedAgendaViewId) -> Unit,
    onDelete: (SavedAgendaViewId) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is SavedAgendaListState.Loading -> {
            LoadingIndicator(modifier = modifier.fillMaxSize())
        }

        is SavedAgendaListState.Loaded -> {
            if (state.views.isEmpty()) {
                EmptyState(
                    title = "No saved views yet",
                    subtitle = "Create one from the agenda tab",
                    modifier = modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.views, key = { it.id.raw }) { view ->
                        SavedAgendaCard(
                            view = view,
                            onClick = { onViewSelected(view.id) },
                            onDelete = { onDelete(view.id) },
                        )
                    }
                }
            }
        }
    }
}

// ===== Previews =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaListContentEmptyPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaListContent(
            state = SavedAgendaListState.Loaded(views = emptyList()),
            onViewSelected = {},
            onDelete = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaListContentLoadedPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaListContent(
            state = SavedAgendaListState.Loaded(
                views = listOf(
                    SavedAgendaView(
                        id = SavedAgendaViewId("v1"),
                        userId = "u1",
                        name = "Work",
                        sectionsJson = """{"sections":[]}""",
                        createdAt = Instant.fromEpochSeconds(1784253600),
                        updatedAt = Instant.fromEpochSeconds(1785496200),
                    ),
                    SavedAgendaView(
                        id = SavedAgendaViewId("v2"),
                        userId = "u1",
                        name = "Personal",
                        sectionsJson = """{"sections":[]}""",
                        createdAt = Instant.fromEpochSeconds(1784336400),
                        updatedAt = Instant.fromEpochSeconds(1785648000),
                    ),
                ),
            ),
            onViewSelected = {},
            onDelete = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaListContentLoadingPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaListContent(
            state = SavedAgendaListState.Loading,
            onViewSelected = {},
            onDelete = {},
        )
    }
}
