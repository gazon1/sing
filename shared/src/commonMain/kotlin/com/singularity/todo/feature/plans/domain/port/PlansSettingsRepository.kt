package com.singularity.todo.feature.plans.domain.port

import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import kotlinx.coroutines.flow.Flow

/**
 * The plan screen's own preference: how the timeline is grouped.
 *
 * A feature-scoped repository rather than a line in the global settings bundle:
 * the granularity is a view setting of one screen, and the namespace keeps it
 * from colliding with another feature's preferences.
 */
interface PlansSettingsRepository {

    /** The granularity in effect for the current profile. */
    val granularity: Flow<PlansGranularity>

    /** Chooses the granularity for the current profile. */
    suspend fun setGranularity(granularity: PlansGranularity)
}
