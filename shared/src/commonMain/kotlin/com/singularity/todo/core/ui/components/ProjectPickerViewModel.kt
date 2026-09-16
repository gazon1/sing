package com.singularity.todo.core.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.CreateProjectInput
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for [ProjectPickerSheet].
 *
 * Owns all domain state: the reactive project list, the inline-create draft,
 * and the create-in-progress flag. The Composable subscribes; this ViewModel is
 * the sole source of truth.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectPickerViewModel(
    private val projectRepo: ProjectsRepository,
    private val createProject: CreateProjectUseCase,
    private val currentUser: ProfileAwareCurrentUser,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(0) },
) : ViewModel() {

    private val _draftName = MutableStateFlow("")
    val draftName: StateFlow<String> = _draftName.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _events = MutableSharedFlow<ProjectPickerEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** Reactive project list — updates automatically when user switches profile. */
    val projects: StateFlow<List<Project>> = currentUser.scopedUserId
        .flatMapLatest { uid -> projectRepo.watchProjects(uid) }
        .stateIn(viewModelScope, sharingStarted(), emptyList())

    /** Raw flow for testing — same source as [projects] but without stateIn caching. */
    internal val projectsFlow: Flow<List<Project>> = currentUser.scopedUserId
        .flatMapLatest { uid -> projectRepo.watchProjects(uid) }

    fun setDraftName(name: String) {
        _draftName.value = name
    }

    fun setCreating(on: Boolean) {
        _isCreating.value = on
        if (!on) _draftName.value = ""
    }

    fun confirmCreate() {
        val name = _draftName.value.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            val uid = currentUser.scopedUserId.value
            val input = CreateProjectInput(
                name = name,
                color = DEFAULT_COLOR,
                parentId = null,
                userId = uid,
            )
            createProject(input)
                .onSuccess {
                    _draftName.value = ""
                    _isCreating.value = false
                    _events.emit(ProjectPickerEvent.Created)
                }
                .onFailure { e ->
                    _events.emit(ProjectPickerEvent.Error(e.message ?: "Failed to create project"))
                }
        }
    }

    companion object {
        private const val DEFAULT_COLOR = 0xFF4CAF50.toInt() // Green
    }
}

sealed interface ProjectPickerEvent {
    data object Created : ProjectPickerEvent
    data class Error(val message: String) : ProjectPickerEvent
}
