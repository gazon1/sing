package com.singularity.todo.feature.projects.presentation.state
import androidx.compose.runtime.Immutable
import com.singularity.todo.core.ui.textfields.FieldOwnedText
import com.singularity.todo.core.ui.textfields.StaticText
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectWithCounts

enum class ProjectSortOrder { Name, Color }

/**
 * The state of the projects list.
 *
 * The filter and the sort order live on the interface rather than on [Content] alone.
 * They describe *what the list is filtered by*, not *what the list holds*, and a list
 * that holds nothing is still filtered: a search that matched no project is a different
 * screen from a project list that has never had a project in it.
 *
 * That distinction is not cosmetic. The search field is bound to [searchQuery], and the
 * ViewModel reads the query it is given back out of the state: a state that dropped the
 * query would make the screen pass an empty string to the field, the field would hand
 * that empty string back to the ViewModel as if the user had cleared it, and the list
 * would never come back.
 *
 * Every variant therefore *carries* the query and the sort order, and carries them as
 * required constructor parameters — no defaults. A default is a value the ViewModel did
 * not have to think about, and the value it quietly supplies is the one the state had
 * before the user typed anything: the transient variants ([Loading], [Error]) would
 * reset the search field of a screen that is merely reporting a failure.
 */
@Immutable
sealed interface ProjectsUiState {

    /**
     * The query the list is filtered by. Empty when nothing is filtered.
     *
     * It is a [FieldOwnedText], so every write to it is named: the field's own
     * text, a seed, or a clear.
     */
    val searchQuery: FieldOwnedText

    /** The order the list is sorted in. */
    val sortOrder: ProjectSortOrder

    /**
     * The projects the user closed, most recently closed first.
     *
     * They left the project list — a closed project is finished, not gone — and this
     * is where they are. Without them, a project closed by mistake could only be got
     * back through one of its tasks, and a project with no tasks could not be got
     * back at all.
     *
     * The list is on the interface rather than on [Content] because it does not
     * depend on the filter: a screen with no open projects still shows the closed
     * ones, and that is the [Empty] state.
     */
    val closedProjects: List<Project>

    /** The list is being loaded for the first time. */
    data class Loading(
        override val searchQuery: FieldOwnedText,
        override val sortOrder: ProjectSortOrder,
        override val closedProjects: List<Project> = emptyList(),
    ) : ProjectsUiState

    /**
     * The list holds no projects.
     *
     * The query is kept so the screen can say *why* the list is empty ("no projects
     * match …" rather than "no projects yet") and so the filter the user typed survives
     * the state that reports the filter found nothing.
     */
    data class Empty(
        override val searchQuery: FieldOwnedText,
        override val sortOrder: ProjectSortOrder,
        override val closedProjects: List<Project> = emptyList(),
    ) : ProjectsUiState

    /** The list holds projects. */
    data class Content(
        val projects: List<ProjectWithCounts>,
        override val searchQuery: FieldOwnedText,
        override val sortOrder: ProjectSortOrder,
        override val closedProjects: List<Project> = emptyList(),
    ) : ProjectsUiState

    /**
     * The list could not be loaded.
     *
     * The query and the sort order travel with the failure: the screen still shows the
     * search field and the sort control, and a state that reset them would clear what
     * the user had typed — the field is bound to the query, so the reset would reach
     * the ViewModel as if the user had cleared it.
     */
    data class Error(
        val message: String,
        override val searchQuery: FieldOwnedText,
        override val sortOrder: ProjectSortOrder,
        override val closedProjects: List<Project> = emptyList(),
    ) : ProjectsUiState
}

/**
 * The query as the search field's source.
 *
 * The ViewModel holds the query the user typed and hands it back unchanged — on the
 * transient states too — so it never replaces the field's value with one the field never
 * produced: the field's echo guard needs no lifting, and there is no counter to read.
 *
 * It is a *view* of the query rather than a piece of the state the machine carries: every
 * variant already carries the query itself, as a required constructor parameter, and the
 * source is what the field is handed. A property of the interface would say the variants
 * have to carry the source too — and then the query and the source would be two values
 * that could disagree.
 *
 * The promise is in the type of the value, not in this sentence: the query is a
 * [FieldOwnedText], and the only ways to write one are the field's own text, a seed and a
 * clear.
 */
val ProjectsUiState.searchQuerySource: StaticText get() = StaticText(searchQuery.text)
