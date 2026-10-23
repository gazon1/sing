package com.singularity.todo.core.tasks

import com.singularity.todo.core.settings.SettingsDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The task settings a process that was told which profile to serve answers with.
 *
 * ## Why this exists
 *
 * The application's settings live in the same store as the active profile id, and a
 * process that was told which profile to serve must not open that store: the
 * application is holding it, and the whole point of the fixed profile is that this
 * process does not go near the application's stored profile state.
 *
 * The tools need exactly one setting — how many days make a task stale — and they
 * already ask for it through the narrow [TasksSettingsRepository] port rather than
 * through the whole settings repository. This is the answer a process with no store
 * gives: the setting's default.
 *
 * ## What the difference is
 *
 * The staleness threshold the agent works with in a fixed-profile server is the
 * default (21 days), not the one the user set in the application. That is the price
 * of not opening the application's store, and it is stated here rather than left to be
 * discovered: an agent that calls a task stale a fortnight later than the user would
 * is answering with the default, not with a wrong number.
 *
 * The write goes nowhere, because this process does not own the settings. The
 * application is what writes them; a tool server serves a profile.
 */
class FixedProfileTasksSettingsRepository : TasksSettingsRepository {

    /** The default threshold: this process has no store to read the user's from. */
    override val stalenessThresholdDays: Flow<Int> =
        flowOf(SettingsDefaults.Tasks.STALENESS_THRESHOLD_DAYS)

    /**
     * There is nowhere for this to go.
     *
     * The tools never write settings — the application does — and a process that does
     * not own the settings has no store to write them to.
     */
    override suspend fun setStalenessThresholdDays(value: Int) {
        // Intentionally empty: this process does not own the application's settings.
    }
}
