package com.singularity.todo.core.di

import com.singularity.todo.feature.projects.CreateProjectUseCase
import com.singularity.todo.feature.projects.ProjectDetailViewModel
import com.singularity.todo.feature.projects.ProjectEditorViewModel
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.projects.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.UpdateProjectUseCase
import com.singularity.todo.feature.projects.usecase.DeleteProjectUseCase
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
    factory { DeleteProjectUseCase(get(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    // ProjectsViewModel with AI deps: registered in aiToolsCoreModule
    // (has nullable ProjectReviewUseCase — handles null gracefully on Android)

    viewModel { (projectId: com.singularity.todo.feature.projects.ProjectId?) ->
        ProjectEditorViewModel(
            projectId = projectId,
            createProject = get(),
            updateProject = get(),
            projectsRepo = get(),
            currentUser = get(),
        )
    }

    viewModel { (id: com.singularity.todo.feature.projects.ProjectId) ->
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
}
