package com.singularity.todo.feature.proposals

import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.proposals.data.ProposalRepositoryImpl
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.EditProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.proposals.presentation.ProposalsViewModel
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * DI for the AI proposal feature.
 *
 * The DAO itself is bound in the platform modules (it comes off `AppDatabase`, which
 * is constructed per-platform); this module binds everything above it so the
 * proposal slice stays self-contained and can be dropped by removing one line from
 * [com.singularity.todo.core.di.domainModule].
 */
fun proposalModule(): Module = module {
    single<ProposalRepository> {
        ProposalRepositoryImpl(
            dao = get(),
            items = get(),
            clock = get(),
            currentUser = get(),
            log = get(),
            crashReporter = get(),
        )
    }

    // Pure planning: all validation, no I/O
    factory<ProposalPlanner> { ProposalPlanner(clock = get()) }

    // Pure execution: no routing, no validation
    factory<ProposalDispatch> {
        ProposalDispatch(
            tasks = get<TaskRepository>(),
            tags = get<TagsRepository>(),
            checklist = get<ChecklistRepository>(),
            timeTracking = get<TimeTrackingRepository>(),
            notes = get<NotesRepository>(),
            projects = get<ProjectsRepository>(),
            deleteProject = get<DeleteProjectUseCase>(),
            clock = get<Clock>(),
        )
    }

    factory<ApplyProposalItemUseCase> {
        ApplyProposalItemUseCase(
            proposals = get(),
            tasks = get(),
            notes = get(),
            projects = get(),
            tags = get(),
            planner = get(),
            dispatch = get(),
        )
    }

    // Changing the value of a proposal before it is applied.
    factory<EditProposalItemUseCase> {
        EditProposalItemUseCase(proposals = get())
    }

    // The proposals screen: the proposals waiting for a decision. An explicit lambda
    // rather than `viewModelOf`, because the ViewModel has defaults for the crash
    // reporter and the scope, and Koin's reflective resolution does not apply Kotlin
    // default arguments.
    viewModel {
        ProposalsViewModel(
            proposals = get(),
            applyProposalItem = get(),
            editProposalItem = get(),
            currentUser = get(),
            crashReporter = get(),
        )
    }
}
