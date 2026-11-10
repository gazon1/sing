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
import com.singularity.todo.feature.projects.domain.port.ProjectsTreeSettingsRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The state of the menu's project tree.
 */
sealed interface ProjectsTreeUiState {
    /**
     * Whether the menu's Projects section is unfolded.
     *
     * A profile with a hundred projects draws a tree longer than the screen,
     * and the tree sits below the entries the user came for — so the section
     * starts folded, and unfolding it is the user's decision.
     */
    val sectionExpanded: Boolean

    /** The projects have not arrived yet. */
    data class Loading(override val sectionExpanded: Boolean) : ProjectsTreeUiState

    /**
     * The nodes the tree shows, in display order.
     *
     * @param nodes The rows, each with its depth.
     */
    data class Loaded(val nodes: ImmutableList<ProjectTreeNode>, override val sectionExpanded: Boolean) :
        ProjectsTreeUiState
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

    /**
     * Fold the menu's whole Projects section away, or unfold it.
     *
     * The section is folded by default: the tree is a way into the projects,
     * not the first thing the menu is for.
     */
    data object ToggleSection : ProjectsTreeIntent
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
 *
 * ## The folding is kept, not just remembered
 *
 * Which projects the user has folded away is theirs: a tree that folds itself
 * back open every time the menu is opened cannot be arranged. The folding is
 * therefore read from and written to [ProjectsTreeSettingsRepository], which
 * keeps it per profile — the arrangement of a work profile and of a personal
 * one are two arrangements.
 *
 * ## The section itself is folded, and the folding is not kept
 *
 * The section around the tree starts folded, because a profile with a hundred
 * projects draws a tree longer than the screen and the tree sits below the
 * entries the user opened the menu for. Unfolding it is a decision of the
 * moment, not an arrangement: it is held in the state and nowhere else, so it
 * does not outlive the ViewModel.
 */
class ProjectsTreeViewModel(
    private val repo: ProjectsRepository,
    private val settings: ProjectsTreeSettingsRepository,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ProjectsTreeUiState, ProjectsTreeIntent, ProjectsTreeUiEvent>(
        initialState = ProjectsTreeUiState.Loading(sectionExpanded = false),
        crashReporter = crashReporter,
        scope = scope,
    ) {
    init {
        vmScope.launch {
            combine(repo.observeAll(), settings.collapsedProjectIds) { projects, collapsed ->
                projects to collapsed
            }.catch { throwable ->
                // The failure crossed a channel hop, and a channel carries a *copy* of
                // the throwable — the original is its cause, so the copy is unwrapped
                // before it is reported.
                val reported = throwable.cause ?: throwable
                crashReporter.report(reported, LOAD_FAILED)
                setState(
                    ProjectsTreeUiState.Loaded(
                        nodes = persistentListOf(),
                        sectionExpanded = currentState.sectionExpanded,
                    ),
                )
                emit(
                    ProjectsTreeUiEvent.ShowError(
                        reported.message ?: "Could not load the projects",
                    ),
                )
            }.collect { (projects, collapsed) ->
                // The section's folding is the user's, so a tree that arrives
                // later — a project created elsewhere — does not fold the
                // section the user unfolded.
                updateState { state ->
                    ProjectsTreeUiState.Loaded(
                        nodes = ProjectTreeBuilder.build(projects, collapsed).toImmutableList(),
                        sectionExpanded = state.sectionExpanded,
                    )
                }
            }
        }
    }

    override fun onIntent(intent: ProjectsTreeIntent) {
        when (intent) {
            is ProjectsTreeIntent.ToggleExpanded -> {
                vmScope.launch {
                    settings.toggleCollapsed(intent.projectId)
                }
            }

            is ProjectsTreeIntent.ToggleSection -> {
                updateState { state ->
                    when (state) {
                        is ProjectsTreeUiState.Loading -> {
                            ProjectsTreeUiState.Loading(sectionExpanded = !state.sectionExpanded)
                        }

                        is ProjectsTreeUiState.Loaded -> {
                            state.copy(sectionExpanded = !state.sectionExpanded)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val LOAD_FAILED = "menu.projects.load_failed"
    }
}
