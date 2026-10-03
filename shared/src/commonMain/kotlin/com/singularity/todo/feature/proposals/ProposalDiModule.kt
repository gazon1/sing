package com.singularity.todo.feature.proposals

import com.singularity.todo.feature.checklist.domain.port.ChecklistRepository
import com.singularity.todo.feature.proposals.data.ProposalRepositoryImpl
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import org.koin.core.module.Module
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
            deleteProject = get<DeleteProjectUseCase>(),
            clock = get<Clock>(),
        )
    }

    factory<ApplyProposalItemUseCase> {
        ApplyProposalItemUseCase(
            proposals = get(),
            tasks = get(),
            notes = get(),
            tags = get(),
            planner = get(),
            dispatch = get(),
        )
    }
}
