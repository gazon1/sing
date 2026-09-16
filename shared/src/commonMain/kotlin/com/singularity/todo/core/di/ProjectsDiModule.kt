package com.singularity.todo.core.di

import com.singularity.todo.core.ui.components.ProjectPickerViewModel
import com.singularity.todo.feature.projects.data.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectEditorViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Projects feature DI: repositories, use cases, ViewModels.
 * Does NOT include AI tools — those live in [aiToolsCoreModule].
 */
fun projectsModule(): org.koin.core.module.Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    single<ProjectsRepository> { ProjectsRepositoryImpl(get(), get()) }

    // ─── Use Cases ──────────────────────────────────────────────────────

    factory { CreateProjectUseCase(get(), get()) }
    factory { UpdateProjectUseCase(get(), get()) }
    factory { DeleteProjectUseCase(get<ProjectsRepository>(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    // ProjectsViewModel with AI deps: registered in aiToolsCoreModule
    // (has nullable ProjectReviewUseCase — handles null gracefully on Android)

    viewModel { (projectId: ProjectId?) ->
        ProjectEditorViewModel(
            projectId = projectId,
            createProject = get(),
            updateProject = get(),
            projectsRepo = get(),
            currentUser = get(),
        )
    }

    viewModel { (id: ProjectId) ->
        ProjectDetailViewModel(
            projectId = id,
            projectRepo = get(),
            taskRepo = get(),
            deleteProject = get(),
            updateProject = get(),
            updateTask = get(),
            createTaskUseCase = get(),
            currentUser = get(),
            clock = get(),
        )
    }

    // ─── Shared component ViewModels ─────────────────────────────────────────

    // NOTE: Using explicit viewModel {} block instead of viewModelOf so that
    // sharingStarted and scopeOverride use their defaults (not resolved via
    // reflection, which can incorrectly match CoroutineScope beans on Android).
    viewModel { ProjectPickerViewModel(get(), get(), get()) }
}
