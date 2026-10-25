package com.singularity.todo.core.di

import com.singularity.todo.core.refresh.CompletedTaskArchiver
import com.singularity.todo.core.refresh.RefreshCoordinator
import com.singularity.todo.core.refresh.RefreshViewModel
import com.singularity.todo.feature.archive.domain.port.ArchiveRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The refresh gesture's dependency graph.
 *
 * Three bindings, and the middle one is the point of the whole change: the
 * [RefreshCoordinator] is a **singleton**. Every screen's refresh asks this
 * one object, so two screens pulling at the same moment produce one archive
 * pass and one synchronisation — not two of each.
 *
 * ## The archiver
 *
 * The core declares the narrow contract it needs
 * ([CompletedTaskArchiver]) rather than taking the archive feature's own
 * port. The feature's port inherits the core's one, so the single
 * implementation behind it serves both, and the dependency between the layers
 * points the way the architecture requires: a feature may depend on the core,
 * never the other way round.
 *
 * ## The scope
 *
 * `scope = get()` resolves the `AutoCloseableCoroutineScope` factory in
 * [coreModule]. The refresh run has to outlive the screen that started it —
 * a user who walks away mid-refresh still expects the archive step to
 * finish — so the run cannot live in a ViewModel's scope.
 */
fun refreshModule(): Module = module {
    single<CompletedTaskArchiver> { get<ArchiveRepository>() }

    // The single owner of the refresh run.
    single {
        RefreshCoordinator(
            archiver = get(),
            syncRepository = get(),
            scope = get(),
        )
    }

    // One ViewModel per screen, but they all ask the same coordinator. The
    // scope is not passed here: the ViewModel derives its own from the
    // reporter, so the two failure paths cannot end up at different
    // destinations.
    viewModel {
        RefreshViewModel(
            coordinator = get(),
            crashReporter = get(),
        )
    }
}
