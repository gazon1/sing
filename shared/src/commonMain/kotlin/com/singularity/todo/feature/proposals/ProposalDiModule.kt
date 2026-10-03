package com.singularity.todo.feature.proposals

import com.singularity.todo.feature.proposals.data.ProposalRepositoryImpl
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

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

    factory<ApplyProposalItemUseCase> {
        ApplyProposalItemUseCase(
            proposals = get(),
            tasks = get(),
            tags = get(),
            checklist = get(),
            timeTracking = get(),
            clock = get(),
        )
    }
}
