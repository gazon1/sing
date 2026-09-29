package com.singularity.todo.feature.agenda

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.agenda.data.RoomSavedAgendaViewsRepository
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaDeps
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaDeps
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListDeps
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaListViewModel
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaScreenMode
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaSeedStore
import com.singularity.todo.feature.agenda.presentation.viewmodel.SavedAgendaViewModel
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * DI module for the Agenda feature.
 *
 * Provides [AgendaViewModel] for each [AgendaDefinition] variant (Inbox, Today, Upcoming, etc.).
 * The [AgendaDefinition] is a runtime parameter — hence [viewModel] with lambda syntax.
 *
 * ## Usage
 *
 * Inject `AgendaViewModel` for a specific preset:
 * ```kotlin
 * val vm: AgendaViewModel = koinViewModel { parametersOf(AgendaPresets.Inbox) }
 * ```
 */
fun agendaModule(): Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    single<SavedAgendaViewsRepository> { RoomSavedAgendaViewsRepository(get(), get(), get()) }

    // ─── Seed store for SavedAgenda Create ────────────────────────────────

    singleOf(::SavedAgendaSeedStore)

    // ─── AgendaViewModel (existing, definition is a runtime param) ───────

    viewModel { (definition: AgendaDefinition) ->
        AgendaViewModel(
            deps = AgendaDeps(
                taskRepo = get<TaskRepository>(),
                logger = Logger.withTag("Agenda"),
            ),
            definition = definition,
        )
    }

    // ─── SavedAgendaListViewModel (no runtime params) ────────────────────
    // Note: viewModelOf does not work here — two-constructor testable-VM pattern
    // creates constructor ambiguity. Use explicit viewModel {} block.
    viewModel {
        SavedAgendaListViewModel(
            deps = SavedAgendaListDeps(
                repo = get(),
                profileRepo = get(),
            ),
        )
    }

    // ─── SavedAgendaViewModel (runtime param: SavedAgendaScreenMode) ───────

    viewModel { (mode: SavedAgendaScreenMode) ->
        SavedAgendaViewModel(
            deps = SavedAgendaDeps(
                repo = get(),
                clock = get<Clock>(),
                log = Logger.withTag("SavedAgenda"),
            ),
            mode = mode,
            seedStore = get(),
        )
    }
}
