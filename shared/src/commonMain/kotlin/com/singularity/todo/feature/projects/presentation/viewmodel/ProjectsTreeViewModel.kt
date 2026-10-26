package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.projects.domain.logic.ProjectTreeBuilder
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.model.ProjectTreeNode
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The state of the menu's project tree.
 */
sealed interface ProjectsTreeUiState {
    /** The projects have not arrived yet. */
    data object Loading : ProjectsTreeUiState

    /**
     * The nodes the tree shows, in display order.
     *
     * @param nodes The rows, each with its depth.
     */
    data class Loaded(val nodes: List<ProjectTreeNode>) : ProjectsTreeUiState
}

/** What the user can ask the tree to do. */
sealed interface ProjectsTreeIntent : MviIntent {

    /**
     * Fold a project's children away, or unfold them.
     *
     * The folding is the user's, so it is remembered: a tree that folds itself
     * back open every time the menu is opened cannot be arranged.
     */
    data class ToggleExpanded(val projectId: ProjectId) : ProjectsTreeIntent
}

/** One-shot events of the tree. */
sealed interface ProjectsTreeUiEvent : MviEvent {
    /** The projects could not be read. */
    data class ShowError(val message: String) : ProjectsTreeUiEvent
}

/**
 * The ViewModel behind the menu's project tree.
 *
 * It watches the projects and re-arranges them into a tree whenever they
 * change, so the tree is live: a project created, renamed or closed elsewhere
 * shows up here without the menu being closed and reopened in between.
 *
 * The tree is drawn from a flat list of nodes rather than from a nested
 * structure — a `LazyColumn` cannot scroll a nested list, and the menu's
 * column scrolls on both platforms.
 */
class ProjectsTreeViewModel(
    private val repo: ProjectsRepository,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ProjectsTreeUiState, ProjectsTreeIntent, ProjectsTreeUiEvent>(
        initialState = ProjectsTreeUiState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /** The projects whose children the user has folded away. */
    private val collapsedIds = MutableStateFlow<Set<ProjectId>>(emptySet())

    init {
        vmScope.launch {
            combine(repo.observeAll(), collapsedIds) { projects, collapsed -> projects to collapsed }
                .catch { throwable ->
                    crashReporter.report(throwable, LOAD_FAILED)
                    setState(ProjectsTreeUiState.Loaded(emptyList()))
                    emit(
                        ProjectsTreeUiEvent.ShowError(
                            throwable.message ?: "Could not load the projects",
                        ),
                    )
                }
                .collect { (projects, collapsed) ->
                    setState(
                        ProjectsTreeUiState.Loaded(
                            ProjectTreeBuilder.build(projects, collapsed),
                        ),
                    )
                }
        }
    }

    override fun onIntent(intent: ProjectsTreeIntent) {
        when (intent) {
            is ProjectsTreeIntent.ToggleExpanded -> {
                collapsedIds.value = collapsedIds.value.let { collapsed ->
                    if (intent.projectId in collapsed) {
                        collapsed - intent.projectId
                    } else {
                        collapsed + intent.projectId
                    }
                }
            }
        }
    }

    private companion object {
        const val LOAD_FAILED = "menu.projects.load_failed"
    }
}
