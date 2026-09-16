package com.singularity.todo.feature.agenda.presentation.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation routes inside the Agenda nested graph.
 *
 * Serialization is required because [androidx.navigation3.runtime.rememberNavBackStack]
 * encodes the stack on each remember via Compose's SaveableStateHolder.
 *
 * In MR1 the agenda has no internal routes — it's a single screen.
 * Future MRs may add detail/sheet routes.
 */
@Serializable
sealed interface AgendaRoute : NavKey {
    // MR1: single screen, no internal routes
}
