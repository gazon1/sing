package com.singularity.todo.feature.projects.domain.port

import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.coroutines.flow.Flow

/**
 * The menu's project tree: which projects the user has folded away.
 *
 * The folding is the user's arrangement of their own work, so it belongs to the
 * profile that made it — the tree of a work profile and the tree of a personal
 * one are two arrangements, not one.
 *
 * A port rather than a line in the global settings bundle: the tree is one
 * screen's state, and the namespace keeps it from colliding with another
 * feature's preferences.
 */
interface ProjectsTreeSettingsRepository {

    /** The projects whose children the current profile has folded away. */
    val collapsedProjectIds: Flow<Set<ProjectId>>

    /**
     * Folds a project's children away, or unfolds them.
     *
     * A toggle and not a setter: the caller asks to fold or unfold, and the
     * repository decides which it is — from the state it holds. A setter would
     * make the caller read the state first, and two callers reading and then
     * writing would fold the same project twice or undo each other's fold.
     */
    suspend fun toggleCollapsed(projectId: ProjectId)
}
