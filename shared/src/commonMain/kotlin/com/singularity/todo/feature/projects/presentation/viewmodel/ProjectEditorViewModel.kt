package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.mvi.MviIntent
import com.singularity.todo.core.ui.mvi.MviViewModel
import com.singularity.todo.feature.projects.domain.model.CreateProjectInput
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.state.ProjectEditorIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectEditorUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectEditorUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Project editor screen ViewModel (create or edit).
 *
 * Owns: project draft with name, color, icon, description.
 * Triggers: field changes, save (create or update).
 * One-shot events: [ProjectEditorUiEvent.NavigateBack].
 *
 * @see ProjectEditorUiState
 * @see ProjectEditorIntent
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectEditorViewModel(
    private val projectId: ProjectId?,
    private val createProject: CreateProjectUseCase,
    private val updateProject: UpdateProjectUseCase,
    private val projectsRepo: ProjectsRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<ProjectEditorUiState, ProjectEditorIntent, ProjectEditorUiEvent>(
    initialState = ProjectEditorUiState(projectId = projectId),
    scope = scope,
) {

    init {
        addCloseable(scope)
    }

    init {
        if (projectId != null) {
            scope.launch { loadProject(projectId) }
        }
    }

    private suspend fun loadProject(id: ProjectId) {
        _state.update { it.copy(loading = true) }
        val project = projectsRepo.observe(id).firstOrNull()
        if (project != null) {
            _state.update {
                it.copy(
                    loading = false,
                    name = project.name,
                    description = project.description ?: "",
                    color = project.color,
                    icon = project.icon,
                    parentId = project.parentId,
                )
            }
        } else {
            _state.update { it.copy(loading = false, errorMessage = "Project not found") }
        }
    }

    override fun onIntent(intent: ProjectEditorIntent) {
        when (intent) {
            is ProjectEditorIntent.NameChanged ->
                _state.update { it.copy(name = intent.name, errorMessage = null) }

            is ProjectEditorIntent.ColorChanged ->
                _state.update { it.copy(color = intent.color) }

            is ProjectEditorIntent.IconChanged ->
                _state.update { it.copy(icon = intent.icon) }

            is ProjectEditorIntent.DescriptionChanged ->
                _state.update { it.copy(description = intent.description) }

            is ProjectEditorIntent.ParentChanged ->
                _state.update { it.copy(parentId = intent.parentId) }

            ProjectEditorIntent.ErrorShown ->
                _state.update { it.copy(errorMessage = null) }

            ProjectEditorIntent.Save -> scope.launch { save() }
        }
    }

    private suspend fun save() {
        val current = state.value
        val validationError = validateName(current.name)
        if (validationError != null) {
            _state.update { it.copy(errorMessage = validationError) }
            return
        }

        _state.update { it.copy(saving = true, errorMessage = null) }
        if (current.projectId == null) {
            // Create mode
            val input = CreateProjectInput(
                name = current.name.trim(),
                color = current.color,
                description = current.description.ifBlank { null },
                icon = current.icon,
                parentId = current.parentId,
            )
            createProject(input).fold(
                onSuccess = { emit(ProjectEditorUiEvent.NavigateBack) },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            saving = false,
                            errorMessage = err.message ?: "Failed to create project",
                        )
                    }
                },
            )
        } else {
            // Edit mode
            updateProject(current.projectId) { existing ->
                existing.copy(
                    name = current.name.trim(),
                    description = current.description.ifBlank { null },
                    color = current.color,
                    icon = current.icon,
                    parentId = current.parentId,
                )
            }.fold(
                onSuccess = { emit(ProjectEditorUiEvent.NavigateBack) },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            saving = false,
                            errorMessage = err.message ?: "Failed to update project",
                        )
                    }
                },
            )
        }
    }

    private fun validateName(name: String): String? = when {
        name.isBlank() -> "Name cannot be blank"
        name.length > 50 -> "Name too long (max 50 characters)"
        else -> null
    }
}
