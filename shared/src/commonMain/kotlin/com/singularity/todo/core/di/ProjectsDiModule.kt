package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.projects.data.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectEditorViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Projects feature DI: repositories, use cases, ViewModels.
 * Does NOT include AI tools — those live in [aiToolsModule].
 */
fun projectsModule(): org.koin.core.module.Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    single<ProjectsRepository> { ProjectsRepositoryImpl(get(), get(), get(), get()) }

    // ─── Use Cases ─────────────────────────────────────────────────────

    factory { CreateProjectUseCase(get(), get(), get()) }
    factoryOf(::UpdateProjectUseCase)
    factory { DeleteProjectUseCase(get<ProjectsRepository>(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    // ProjectsViewModel with AI deps: registered in aiToolsModule
    // (has nullable ProjectReviewUseCase — handles null gracefully on Android)

    viewModel { (projectId: ProjectId?) ->
        ProjectEditorViewModel(
            projectId = projectId,
            createProject = get(),
            updateProject = get(),
            projectsRepo = get(),
            crashReporter = get(),
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
            projectReminders = get(),
            clock = get(),
            log = Logger.withTag("ProjectDetail"),
            crashReporter = get(),
        )
    }

    // ─── Shared component ViewModels ─────────────────────────────────────────
}
