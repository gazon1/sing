package com.singularity.todo.feature.agenda

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaDeps
import com.singularity.todo.feature.agenda.presentation.viewmodel.AgendaViewModel
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

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
    viewModel { (definition: AgendaDefinition) ->
        AgendaViewModel(
            deps = AgendaDeps(
                taskRepo = get<TaskRepository>(),
                currentUser = get(),
                logger = Logger.withTag("Agenda"),
            ),
            definition = definition,
        )
    }
}
