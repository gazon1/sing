package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.ListPickerItem
import com.singularity.todo.core.ui.components.ListPickerSheet
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.presentation.components.SavedAgendaCard
import com.singularity.todo.feature.agenda.presentation.nav.LocalAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.nav.PreviewAgendaNavigator
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListEvent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListIntent
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListState
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant

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

    var pendingCopyViewId by remember { mutableStateOf<SavedAgendaViewId?>(null) }

    NotificationHost<SavedAgendaListEvent>(
        events = viewModel.events,
        mapper = { e ->
            when (e) {
                is SavedAgendaListEvent.ShowError -> Notification.Error(e.message)
                is SavedAgendaListEvent.CopySuccess -> Notification.Text("Copied to ${e.targetProfileName}", null)
            }
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Views") },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navigator.openSavedAgendaCreate(AgendaPresets.Inbox) },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create view")
            }
        },
        modifier = modifier,
    ) { paddingValues ->
        SavedAgendaListContent(
            state = state,
            onViewSelected = { viewId -> navigator.openSavedAgendaEdit(viewId) },
            onDelete = { viewId -> viewModel.onIntent(SavedAgendaListIntent.Delete(viewId)) },
            onEdit = { viewId -> navigator.openSavedAgendaEdit(viewId) },
            onCopyToProfile = { viewId -> pendingCopyViewId = viewId },
            modifier = Modifier.padding(paddingValues),
        )
    }

    // Profile picker for copy-to-profile
    pendingCopyViewId?.let { viewId ->
        val profileRepo: com.singularity.todo.feature.profile.ProfileRepository = koinInject()
        ProfilePickerSheet(
            viewId = viewId,
            profileRepo = profileRepo,
            onDismiss = { pendingCopyViewId = null },
            onPick = { profileId ->
                viewModel.onIntent(SavedAgendaListIntent.CopyToProfile(viewId, profileId))
                pendingCopyViewId = null
            },
        )
    }
}

/**
 * Content composable for the saved agenda views list screen.
 * Stateless — receives [SavedAgendaListState] and emits callbacks.
 */
@Composable
fun SavedAgendaListContent(
    state: SavedAgendaListState,
    onViewSelected: (SavedAgendaViewId) -> Unit,
    onDelete: (SavedAgendaViewId) -> Unit,
    onEdit: (SavedAgendaViewId) -> Unit,
    onCopyToProfile: (SavedAgendaViewId) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        SavedAgendaListState.Loading -> {
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
                            onEdit = { onEdit(view.id) },
                            onCopyToProfile = { onCopyToProfile(view.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bottom sheet for selecting a target profile when copying a saved view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfilePickerSheet(
    viewId: SavedAgendaViewId,
    profileRepo: com.singularity.todo.feature.profile.ProfileRepository,
    onDismiss: () -> Unit,
    onPick: (com.singularity.todo.feature.profile.ProfileId) -> Unit,
) {
    val profiles by profileRepo.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    ListPickerSheet(
        title = "Copy to profile",
        items = profiles.map { profile ->
            ListPickerItem(
                key = profile.id,
                label = profile.name,
                subtitle = if (profile.isDefault) "Default" else null,
                leading = { Text(profile.emoji, style = MaterialTheme.typography.titleLarge) },
            )
        },
        onItemSelected = onPick,
        onDismiss = onDismiss,
        sheetState = sheetState,
    )
}

// ===== Previews =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaListContentEmptyPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaListContent(
            state = SavedAgendaListState.Loaded(emptyList()),
            onViewSelected = {},
            onDelete = {},
            onEdit = {},
            onCopyToProfile = {},
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SavedAgendaListContentLoadedPreview() = PreviewAgendaNavigator {
    PreviewThemed(darkTheme = false) {
        SavedAgendaListContent(
            state = SavedAgendaListState.Loaded(
                listOf(
                    SavedAgendaView(
                        id = SavedAgendaViewId("v1"),
                        userId = UserId("u1"),
                        name = "Weekly Review",
                        sectionsJson = "{}",
                        createdAt = Instant.fromEpochSeconds(1784253600),
                        updatedAt = Instant.fromEpochSeconds(1785496200),
                    ),
                    SavedAgendaView(
                        id = SavedAgendaViewId("v2"),
                        userId = UserId("u1"),
                        name = "Focus Today",
                        sectionsJson = "{}",
                        createdAt = Instant.fromEpochSeconds(1784253600),
                        updatedAt = Instant.fromEpochSeconds(1785496200),
                    ),
                ),
            ),
            onViewSelected = {},
            onDelete = {},
            onEdit = {},
            onCopyToProfile = {},
        )
    }
}
