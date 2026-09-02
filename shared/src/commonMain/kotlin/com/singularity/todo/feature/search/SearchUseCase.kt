package com.singularity.todo.feature.search

import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.tags.TagsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class SearchResults(
    val tasks: List<Task>,
    val notes: List<Note>,
    val projects: List<Project>,
    val tags: List<Tag>
)

class SearchUseCase(
    private val taskRepo: TaskRepository,
    private val noteRepo: NotesRepository,
    private val projectRepo: ProjectsRepository,
    private val tagRepo: TagsRepository
) {
    operator fun invoke(
        query: String,
        userId: String
    ): Flow<SearchResults> = combine(
        taskRepo.watchTasks(com.singularity.todo.feature.tasks.UserId.fromString(userId), com.singularity.todo.feature.tasks.TaskFilter.Search(query)),
        noteRepo.searchNotes(query),
        projectRepo.watchProjects(userId),
        tagRepo.watchTags(userId)
    ) { tasks, notes, projects, tags ->
        SearchResults(
            tasks = tasks,
            notes = notes,
            projects = projects.filter { it.name.contains(query, ignoreCase = true) },
            tags = tags.filter { it.name.contains(query, ignoreCase = true) }
        )
    }
}
