package com.singularity.todo.feature.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.platform.systemToday
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ConfirmActionDialog
import com.singularity.todo.core.ui.components.ContentState
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.components.StatefulContent
import com.singularity.todo.core.ui.components.TopAppBar
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.LocalAppNavigator
import com.singularity.todo.feature.tasks.domain.logic.Blocking
import com.singularity.todo.feature.tasks.presentation.components.TaskCard
import com.singularity.todo.feature.tasks.presentation.components.TaskCardActions
import com.singularity.todo.preview.PreviewSamples
import kotlinx.collections.immutable.toImmutableSet
import org.koin.compose.viewmodel.koinViewModel

@Composable
@Suppress("LongMethod")
fun ArchiveScreen(
    viewModel: ArchiveViewModel = koinViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    Scaffold(
        topBar = {
            ArchiveTopBar(
                state = state,
                onBack = onBack,
                onArchiveCompleted = { viewModel.onIntent(ArchiveIntent.ArchiveCompletedTasks) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
        ) {
            // No RefreshableContent here on purpose. The shared refresh gesture's
            // first step is the bulk archive, so a pull on this screen would be the
            // unconfirmed bulk write the overflow action exists to replace. The other
            // screens keep the gesture; this one's only bulk path is the confirmed
            // overflow item.
            when (val s = state) {
                ArchiveUiState.Loading -> {
                    LoadingIndicator()
                }

                is ArchiveUiState.Error -> {
                    StatefulContent(
                        state = ContentState.Error(AppError.Unknown(s.message, code = "archive.load_failed")),
                        emptyTitle = "",
                        onRetry = { viewModel.onIntent(ArchiveIntent.Retry) },
                        modifier = Modifier.fillMaxSize(),
                    ) { _, _ -> }
                }

                is ArchiveUiState.Content -> {
                    if (s.tasks.isEmpty()) {
                        EmptyState(
                            title = "Archive is empty",
                            subtitle = "Completed tasks you archive will appear here",
                            testTag = TestTags.ARCHIVE_EMPTY,
                        )
                    } else {
                        val blockedTaskIds = remember(s.tasks) { Blocking.blockedTaskIds(s.tasks).toImmutableSet() }
                        LazyColumn(
                            modifier = Modifier.widthIn(max = 640.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            items(s.tasks, key = { it.id.value }) { task ->
                                TaskCard(
                                    task = task,
                                    today = systemToday(),
                                    blockedTaskIds = blockedTaskIds,
                                    onClick = {
                                        navigator.navigate(
                                            AppDestination.TasksGraph(
                                                AppDestination.TasksStartRoute.Detail(task.id),
                                            ),
                                        )
                                    },
                                    actions =
                                        TaskCardActions(
                                            onAiClick = {
                                                navigator.navigate(
                                                    AppDestination.TasksGraph(
                                                        AppDestination.TasksStartRoute.Detail(task.id),
                                                    ),
                                                )
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    NotificationHost(
        events = viewModel.events,
        mapper = { it.toNotification() },
        modifier = Modifier.testTag(TestTags.ARCHIVE_NOTIFICATION_HOST),
    )
}

/**
 * The archive top bar.
 *
 * The bulk action lives in the overflow rather than as a full-width button: it is
 * a secondary, destructive-in-effect sweep, not the screen's primary job. The bar
 * publishes its action row to the Android shell (see [TopAppBar]), so the same
 * menu is drawn by the shell on Android and by the bar itself on desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveTopBar(
    state: ArchiveUiState,
    onBack: () -> Unit,
    onArchiveCompleted: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val content = state as? ArchiveUiState.Content
    val eligible = content?.eligibleCount ?: 0
    val archiving = content?.archiving == true

    // The dialog is *closed*, not merely hidden, when the count reaches zero: the
    // `if` below only stops drawing it, and leaving `confirming` true made it
    // reappear by itself if another task became completed later. The user confirmed
    // nothing while the count was zero, and the old dialog is not a pending answer.
    LaunchedEffect(eligible) {
        if (eligible == 0) confirming = false
    }

    TopAppBar(
        title = { Text("Archive") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag(TestTags.ARCHIVE_OVERFLOW),
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    // Renders in its own popup window — mapTestTagsAsResourceIds required
                    // so Maestro id: selectors can find items inside the menu.
                    modifier = Modifier.mapTestTagsAsResourceIds(),
                ) {
                    DropdownMenuItem(
                        text = { Text("Archive completed tasks ($eligible)") },
                        onClick = {
                            menuOpen = false
                            confirming = true
                        },
                        enabled = eligible > 0 && !archiving,
                        modifier = Modifier.testTag(TestTags.ARCHIVE_BULK_ACTION),
                    )
                }
            }
        },
    )

    if (confirming && eligible > 0) {
        ConfirmActionDialog(
            title = "Archive completed tasks?",
            text =
                "Move $eligible completed ${if (eligible == 1) "task" else "tasks"} to Archive? " +
                    "They leave active lists and can be restored individually from Archive.",
            confirmButtonText = if (eligible == 1) "Archive 1 task" else "Archive $eligible tasks",
            onConfirm = {
                confirming = false
                onArchiveCompleted()
            },
            onDismiss = { confirming = false },
        )
    }
}

private fun ArchiveUiEvent.toNotification(): Notification =
    when (this) {
        is ArchiveUiEvent.Archived -> Notification.Text(title = "Archived", text = message)
        ArchiveUiEvent.NothingToArchive -> Notification.Text(title = "Archive", text = "Nothing to archive")
        is ArchiveUiEvent.Error -> Notification.Error(message)
    }

// ===== Preview =====

@Composable
private fun ArchiveContentPreview(state: ArchiveUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (state) {
                ArchiveUiState.Loading -> {
                    LoadingIndicator()
                }

                is ArchiveUiState.Error -> {
                    EmptyState(title = "Error", subtitle = state.message)
                }

                is ArchiveUiState.Content -> {
                    if (state.tasks.isEmpty()) {
                        EmptyState(
                            title = "Archive is empty",
                            subtitle = "Completed tasks you archive will appear here",
                            testTag = TestTags.ARCHIVE_EMPTY,
                        )
                    } else {
                        val blockedTaskIds =
                            remember(state.tasks) {
                                Blocking.blockedTaskIds(state.tasks).toImmutableSet()
                            }
                        LazyColumn(
                            modifier = Modifier.widthIn(max = 640.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            items(state.tasks, key = { it.id.value }) { task ->
                                TaskCard(
                                    task = task,
                                    today = systemToday(),
                                    blockedTaskIds = blockedTaskIds,
                                    onClick = noopClick,
                                    actions = TaskCardActions(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun ArchiveScreenContentPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        ArchiveContentPreview(
            ArchiveUiState.Content(
                tasks =
                    listOf(
                        PreviewSamples.task("t1", "Completed task 1", completed = true),
                        PreviewSamples.task("t2", "Completed task 2", completed = true),
                        PreviewSamples.task("t3", "Another completed task", completed = true),
                    ),
            ),
        )
    }

@Preview
@Composable
private fun ArchiveScreenEmptyPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        ArchiveContentPreview(ArchiveUiState.Content(tasks = emptyList()))
    }

@Preview
@Composable
private fun ArchiveScreenLoadingPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        ArchiveContentPreview(ArchiveUiState.Loading)
    }
