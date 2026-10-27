package com.singularity.todo.core.di

import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.data.DataStoreProjectsTreeSettingsRepository
import com.singularity.todo.feature.projects.data.ProjectsRepositoryImpl
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.port.ProjectsTreeSettingsRepository
import com.singularity.todo.feature.projects.domain.usecase.CloseProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.nav.ProjectsOpener
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectEditorViewModel
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsTreeViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Projects feature DI: repositories, use cases, ViewModels.
 * Does NOT include AI tools — those live in [aiToolsModule].
 */
fun projectsModule(): org.koin.core.module.Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    // Named resolution, not positional: the repository now takes two DAOs of the
    // same entity family, and `get(), get()` would misbind silently the moment the
    // parameter order changes. `TagGroupRepository` binds the same way, for the same
    // reason.
    single<ProjectsRepository> {
        ProjectsRepositoryImpl(
            projectDao = get(),
            inheritedTagGroupDao = get(),
            clock = get(),
            currentUser = get(),
            syncRepository = get(),
            unitOfWork = get(),
        )
    }

    // ─── Use Cases ─────────────────────────────────────────────────────

    factory { CreateProjectUseCase(get(), get(), get()) }
    factoryOf(::UpdateProjectUseCase)
    factory { DeleteProjectUseCase(get<ProjectsRepository>(), get()) }
    factory { CloseProjectUseCase(get(), get(), get(), get()) }

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
            tagGroupRepo = get(),
            deleteProject = get(),
            closeProject = get(),
            updateProject = get(),
            updateTask = get(),
            createTaskUseCase = get(),
            projectReminders = get(),
            notificationsSettings = get(),
            clock = get(),
            crashReporter = get(),
        )
    }

    // ─── The menu's project tree ─────────────────────────────────────────────

    // The rendezvous between the menu's tree and this graph. A singleton,
    // because it is app state: the tree and the graph are two different
    // compositions that have to meet.
    single { ProjectsOpener() }

    // Which projects the user has folded away. The folding is theirs, so it is
    // kept per profile — see the repository's own note on why the key carries
    // the scoped user id.
    single<ProjectsTreeSettingsRepository> {
        DataStoreProjectsTreeSettingsRepository(
            dataStore = get(),
            userId = get<ProfileAwareCurrentUser>().scopedUserId,
        )
    }

    viewModel {
        ProjectsTreeViewModel(
            repo = get(),
            settings = get(),
            crashReporter = get(),
        )
    }

    // ─── Shared component ViewModels ─────────────────────────────────────────
}
