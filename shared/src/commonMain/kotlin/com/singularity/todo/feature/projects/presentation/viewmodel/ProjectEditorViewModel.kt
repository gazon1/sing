package com.singularity.todo.feature.projects.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.ui.state.updateState
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.CreateProjectInput
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.state.ProjectEditorIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectEditorUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectEditorUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Project editor screen ViewModel (create or edit).
 *
 * Owns: project draft with name, color, icon, description.
 * Triggers: field changes, save (create or update).
 * One-shot events: [ProjectEditorUiEvent.NavigateBack], [ProjectEditorUiEvent.ShowError].
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
    private val currentUser: ProfileAwareCurrentUser,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        projectId: ProjectId?,
        createProject: CreateProjectUseCase,
        updateProject: UpdateProjectUseCase,
        projectsRepo: ProjectsRepository,
        currentUser: ProfileAwareCurrentUser,
    ) : this(
        projectId = projectId,
        createProject = createProject,
        updateProject = updateProject,
        projectsRepo = projectsRepo,
        currentUser = currentUser,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _state = MutableStateFlow(ProjectEditorUiState(projectId = projectId))
    val state: StateFlow<ProjectEditorUiState> = _state.asStateFlow()

    private val _events = Channel<ProjectEditorUiEvent>(Channel.BUFFERED)
    val events: kotlinx.coroutines.flow.Flow<ProjectEditorUiEvent> = _events.receiveAsFlow()

    init {
        if (projectId != null) {
            loadProject(projectId)
        }
    }

    private fun loadProject(id: ProjectId) {
        scope.launch {
            _state.updateState { it.copy(loading = true) }
            val project = projectsRepo.watchProject(id).firstOrNull()
            if (project != null) {
                _state.updateState {
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
                _state.updateState { it.copy(loading = false, errorMessage = "Project not found") }
            }
        }
    }

    fun processIntent(intent: ProjectEditorIntent) {
        when (intent) {
            is ProjectEditorIntent.NameChanged ->
                _state.updateState { it.copy(name = intent.name, errorMessage = null) }

            is ProjectEditorIntent.ColorChanged ->
                _state.updateState { it.copy(color = intent.color) }

            is ProjectEditorIntent.IconChanged ->
                _state.updateState { it.copy(icon = intent.icon) }

            is ProjectEditorIntent.DescriptionChanged ->
                _state.updateState { it.copy(description = intent.description) }

            is ProjectEditorIntent.ParentChanged ->
                _state.updateState { it.copy(parentId = intent.parentId) }

            ProjectEditorIntent.ErrorShown ->
                _state.updateState { it.copy(errorMessage = null) }

            ProjectEditorIntent.Save -> save()
        }
    }

    private fun save() {
        val current = _state.value
        val validationError = validateName(current.name)
        if (validationError != null) {
            _state.updateState { it.copy(errorMessage = validationError) }
            return
        }

        _state.updateState { it.copy(saving = true, errorMessage = null) }
        scope.launch {
            val userId = currentUser.scopedUserId.value
            if (current.projectId == null) {
                // Create mode
                val input = CreateProjectInput(
                    name = current.name.trim(),
                    color = current.color,
                    description = current.description.ifBlank { null },
                    icon = current.icon,
                    parentId = current.parentId,
                    userId = userId,
                )
                createProject(input).fold(
                    onSuccess = { _events.trySend(ProjectEditorUiEvent.NavigateBack) },
                    onFailure = { err ->
                        _state.updateState {
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
                    onSuccess = { _events.trySend(ProjectEditorUiEvent.NavigateBack) },
                    onFailure = { err ->
                        _state.updateState {
                            it.copy(
                                saving = false,
                                errorMessage = err.message ?: "Failed to update project",
                            )
                        }
                    },
                )
            }
        }
    }

    private fun validateName(name: String): String? = when {
        name.isBlank() -> "Name cannot be blank"
        name.length > 50 -> "Name too long (max 50 characters)"
        else -> null
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
