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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.ui.components.EmptyState
import com.singularity.todo.core.ui.components.LoadingIndicator
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.feature.projects.presentation.components.ActiveSheet
import com.singularity.todo.feature.projects.presentation.components.CurrentProjectContent
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailActions
import com.singularity.todo.feature.projects.presentation.components.ProjectDetailSheetsHost
import com.singularity.todo.feature.projects.presentation.nav.LocalProjectsNavigator
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import kotlin.time.Clock

// ─── Content ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailContent(viewModel: ProjectDetailViewModel, modifier: Modifier = Modifier) {
    val nav = LocalProjectsNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastEditedAt by viewModel.lastEditedAt.collectAsStateWithLifecycle()
    val clock: Clock = viewModel.clock
    val sheets = rememberDialogState<ActiveSheet>()
    val snackbarHostState = remember { SnackbarHostState() }
    val draftState by viewModel.draftState.state.collectAsStateWithLifecycle()

    val actions = remember {
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

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ProjectDetailUiEvent.NavigateBack -> nav.back()
                is ProjectDetailUiEvent.ShowError -> snackbarHostState.showSnackbar(event.message)
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
                    availableTasks = s.availableTasks,
                    actions = actions,
                    nav = nav,
                )
            }
        }
    }

    // ─── Sheets ────────────────────────────────────────────────────────────
    if (sheets.active != null) {
        BottomSheetHost(onDismiss = { sheets.dismiss() }) {
            val content = (contentState as? ProjectDetailUiState.Content)
            ProjectDetailSheetsHost(
                activeSheet = sheets.active,
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
                        onUpdateColor = { actions.onUpdateColor(it) },
                        onUpdateIcon = { actions.onUpdateIcon(it) },
                        onUpdateParent = { actions.onUpdateParent(it) },
                        onUpdateDueDate = { actions.onUpdateDueDate(it) },
                        onUpdateName = { actions.onUpdateName(it) },
                        onUpdateDescription = { actions.onUpdateDescription(it) },
                        onDelete = { actions.onDelete() },
                        onToggleArchive = { actions.onToggleArchive() },
                        onSetReminder = { offset -> actions.onSetReminder(offset.minutes) },
                        onNavigateToChild = { id -> actions.onNavigateToChild(id) },
                    )
                },
                parentOptions = content?.parentOptions.orEmpty(),
                onSheetDismiss = { sheets.dismiss() },
            )
        }
    }
}
