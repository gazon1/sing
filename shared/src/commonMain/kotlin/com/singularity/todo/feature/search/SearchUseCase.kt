package com.singularity.todo.feature.search

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class SearchResults(val tasks: List<Task>, val notes: List<Note>, val projects: List<Project>, val tags: List<Tag>)

class SearchUseCase(
    private val taskRepo: TaskRepository,
    private val noteRepo: NotesRepository,
    private val projectRepo: ProjectsRepository,
    private val tagRepo: TagsRepository,
) {
    operator fun invoke(query: String): Flow<SearchResults> = combine(
        taskRepo.observeByFilter(TaskFilter.Search(query)),
        noteRepo.searchNotesForCurrentUser(query),
        projectRepo.observeAll(),
        tagRepo.observeAll(),
    ) { tasks, notes, projects, tags ->
        SearchResults(
            tasks = tasks,
            notes = notes,
            projects = projects.filter { it.name.contains(query, ignoreCase = true) },
            tags = tags.filter { it.name.contains(query, ignoreCase = true) },
        )
    }
}
