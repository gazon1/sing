package com.singularity.todo.feature.projects.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.ui.components.CollectEvents
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.OverlayState
import com.singularity.todo.core.ui.components.rememberOverlayState
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.feature.projects.presentation.components.ActiveSheet
import com.singularity.todo.feature.projects.presentation.components.CurrentProjectContent
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailActions
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailSheetsHost
import com.singularity.todo.feature.projects.presentation.nav.LocalProjectsNavigator
import com.singularity.todo.feature.projects.presentation.nav.ProjectsNavigator
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import kotlinx.coroutines.launch
import kotlin.time.Clock

// ─── Content ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailContent(viewModel: ProjectDetailViewModel, modifier: Modifier = Modifier) {
    val nav = LocalProjectsNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastEditedAt by viewModel.lastEditedAt.collectAsStateWithLifecycle()
    val clock: Clock = viewModel.clock
    val sheets = rememberOverlayState<ActiveSheet>()
    val snackbarHostState = remember { SnackbarHostState() }
    val draftState by viewModel.draftState.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val actions = rememberProjectDetailActions(sheets = sheets, nav = nav, viewModel = viewModel)

    CollectEvents(viewModel.events) { event ->
        when (event) {
            ProjectDetailUiEvent.NavigateBack -> nav.back()

            is ProjectDetailUiEvent.ShowError -> scope.launch {
                snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val contentState = state
    Scaffold(
        modifier = modifier,
        topBar = {
            ProjectDetailTopBar(
                state = state,
                actions = actions,
                onBack = { nav.back() },
            )
        },
        bottomBar = {
            if (contentState is ProjectDetailUiState.Content) {
                ProjectBottomActionBar(
                    isArchived = contentState.ui.project.isDeleted,
                    actions = actions,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val s = state) {
            ProjectDetailUiState.Loading -> LoadingIndicator(Modifier.padding(padding))

            ProjectDetailUiState.NotFound -> EmptyState(
                title = "Project not found",
                modifier = Modifier.padding(padding),
            )

            is ProjectDetailUiState.Content -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                ProjectHeroSection(
                    ui = s.ui,
                    lastEditedAt = lastEditedAt,
                    now = clock.now(),
                    nameDraft = draftState.name,
                    descriptionDraft = draftState.description,
                    actions = actions,
                )
                ProjectMetaChipsRow(ui = s.ui, actions = actions)
                ProjectBodySection(
                    ui = s.ui,
                    hideCompleted = s.hideCompleted,
                    hideBlocked = s.hideBlocked,
                    availableTasks = s.availableTasks,
                    actions = actions,
                    nav = nav,
                )
            }
        }
    }

    // ─── Sheets ────────────────────────────────────────────────────────────
    if (sheets.sheet != null) {
        BottomSheetHost(onDismiss = { sheets.dismissSheet() }) {
            val content = (contentState as? ProjectDetailUiState.Content)
            ProjectDetailSheetsHost(
                activeSheet = sheets.sheet,
                currentContent = content?.let { c ->
                    CurrentProjectContent(
                        name = c.ui.project.name,
                        color = c.ui.project.color,
                        icon = c.ui.project.icon,
                        parentId = c.ui.parent?.id,
                        dueDate = c.ui.project.dueDate,
                        isArchived = c.ui.project.isDeleted,
                        childProjects = c.ui.childProjects,
                        reminderOffset = content.ui.reminderOffsetMinutes?.let { m ->
                            ReminderOffset.entries.firstOrNull { it.minutes == m }
                        },
                        actions = actions,
                    )
                },
                parentOptions = content?.parentOptions.orEmpty(),
                onSheetDismiss = { sheets.dismissSheet() },
            )
        }
    }
}

// ─── Actions factory ─────────────────────────────────────────────────────────

/**
 * Builds the [ProjectDetailActions] dispatcher for [ProjectDetailContent].
 *
 * Routing intents ([ProjectDetailIntent.Routing]) are pure screen concerns — they open a
 * sheet on [sheets] or push a child route via [nav] — so they never reach the ViewModel.
 * Domain intents go straight to [ProjectDetailViewModel.onIntent].
 *
 * Hoisted out of the composable so the `when` is a top-level, exhaustively-checked
 * expression instead of an anonymous lambda. `remember` is keyed on all three
 * collaborators: an unkeyed `remember` would capture a stale [nav] (from
 * [LocalProjectsNavigator.current]) for the lifetime of the composition.
 */
@Composable
private fun rememberProjectDetailActions(
    sheets: OverlayState<ActiveSheet>,
    nav: ProjectsNavigator,
    viewModel: ProjectDetailViewModel,
): ProjectDetailActions = remember(sheets, nav, viewModel) {
    ProjectDetailActions { intent ->
        when (intent) {
            is ProjectDetailIntent.Routing.OpenColorSheet -> sheets.show(ActiveSheet.PickColor)

            is ProjectDetailIntent.Routing.OpenIconSheet -> sheets.show(ActiveSheet.PickIcon)

            is ProjectDetailIntent.Routing.OpenParentSheet -> sheets.show(
                ActiveSheet.PickParent(intent.currentParentId),
            )

            is ProjectDetailIntent.Routing.OpenDueDateSheet -> sheets.show(ActiveSheet.PickDueDate)

            is ProjectDetailIntent.Routing.OpenChildrenSheet -> sheets.show(ActiveSheet.ShowChildren)

            is ProjectDetailIntent.Routing.OpenDeleteSheet -> sheets.show(ActiveSheet.ConfirmDelete)

            is ProjectDetailIntent.Routing.OpenArchiveSheet -> sheets.show(ActiveSheet.ConfirmArchive)

            is ProjectDetailIntent.Routing.OpenReminderSheet -> sheets.show(ActiveSheet.PickReminder)

            is ProjectDetailIntent.Routing.OpenAttachmentSheet -> sheets.show(ActiveSheet.AddAttachment)

            is ProjectDetailIntent.Routing.NavigateToChild -> nav.openDetail(intent.projectId)

            is ProjectDetailIntent.Domain -> viewModel.onIntent(intent)
        }
    }
}
