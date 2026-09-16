package com.singularity.todo.feature.search.presentation.nav

import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * No-op [SearchNavigator] for use in @Preview composables where [LocalSearchNavigator]
 * is not available (i.e., outside of [SearchNavGraph]).
 */
class PreviewSearchNavigator :
    SearchNavigator(
        onExitGraph = {},
    ) {
    override fun openTask(taskId: TaskId) { /* no-op in preview */ }
    override fun openNote(noteId: NoteId) { /* no-op in preview */ }
    override fun openProject(projectId: ProjectId) { /* no-op in preview */ }
}
