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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.sheet.ListPickerItem
import com.singularity.todo.core.ui.components.sheet.ListPickerSheet
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
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
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingCopyViewId by remember { mutableStateOf<SavedAgendaViewId?>(null) }

    NotificationHost<SavedAgendaListEvent>(
        events = viewModel.events,
        mapper = { e ->
            when (e) {
                is SavedAgendaListEvent.ShowError -> Notification.Error(e.message)

                is SavedAgendaListEvent.CopySuccess -> {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Copied to ${e.targetProfileName}",
                            duration = SnackbarDuration.Short,
                        )
                    }
                    Notification.None
                }
            }
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Views") },
                navigationIcon = {
                    IconButton(
                        onClick = { navigator.back() },
                        modifier = Modifier.testTag(TestTags.SAVED_AGENDA_LIST_BACK),
                    ) {
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
                modifier = Modifier.testTag(TestTags.SAVED_AGENDA_CREATE_FAB),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create view")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        SavedAgendaListContent(
            state = state,
            onViewSelected = { viewId -> navigator.openSavedAgendaResults(viewId) },
            onDelete = { viewId -> viewModel.onIntent(SavedAgendaListIntent.Delete(viewId)) },
            onEdit = { viewId -> navigator.openSavedAgendaEdit(viewId) },
            onCopyToProfile = { viewId -> pendingCopyViewId = viewId },
            modifier = Modifier.padding(paddingValues),
        )
    }

    // Profile picker for copy-to-profile
    pendingCopyViewId?.let { viewId ->
        val profileRepo: com.singularity.todo.feature.profile.domain.port.ProfileRepository = koinInject()
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
                    testTag = TestTags.SAVED_AGENDA_EMPTY_TITLE,
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
    profileRepo: ProfileRepository,
    onDismiss: () -> Unit,
    onPick: (ProfileId) -> Unit,
) {
    val profiles by profileRepo.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    ListPickerSheet(
        title = "Copy to profile",
        items = profiles.map { profile ->
            ListPickerItem(
                key = profile.id,
                label = profile.name,
                subtitle = if (profile.isDefault) "Default" else null,
                leading = { Text(profile.emoji, style = MaterialTheme.typography.titleLarge) },
                testTag = TestTags.profileItem(profile.name),
            )
        },
        onItemSelected = onPick,
        onDismiss = onDismiss,
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
