package com.singularity.todo.feature.settings

import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection

/**
 * Re-exports [SettingsIntent] from [core.settings] so that existing importers
 * (sub-screens, ViewModels) don't need to change their import paths.
 */
typealias SettingsIntent = SettingsIntent

/**
 * Settings screen UI state.
 *
 * ## Invariant
 *
 * [Content] is the only state the screen ever sees: `SettingsViewModel` initialises with
 * `Content()` and never emits anything else, so the `when` in `SettingsScreen` is
 * exhaustive by construction rather than by luck. Do not add a `Loading` or `Error`
 * variant without also producing it — a branch that renders an unreachable state is a
 * branch that is never verified.
 *
 * The `Loading` and `Error` variants that used to sit here had no producer: the screen
 * carried two render branches for states that could not occur, and they were dead code
 * that no test could cover. A transient failure belongs in [Content.errorMessage], which
 * the ViewModel already clears on the next successful action.
 *
 * ## Architecture note
 * [Content] holds typed contributor sections plus ephemeral (non-persisted) state.
 * Error state is a top-level field because it is not a contributor section.
 */
sealed interface SettingsUiState {
    data class Content(
        // ── Typed contributor sections ─────────────────────────────────────────
        val appearance: SettingsSection.Appearance = SettingsSection.Appearance(),
        val notifications: SettingsSection.Notifications = SettingsSection.Notifications(),
        val workSchedule: SettingsSection.WorkSchedule = SettingsSection.WorkSchedule(),
        val greeting: SettingsSection.Greeting = SettingsSection.Greeting(),
        val ai: SettingsSection.Ai = SettingsSection.Ai(),
        val defaultAgendaView: SettingsSection.DefaultAgendaView = SettingsSection.DefaultAgendaView(),

        // ── Ephemeral state (not persisted) ────────────────────────────────────
        /** AI test result, fetched model list, fetch errors — UI-only state. */
        val aiEphemeral: EphemeralState.Ai = EphemeralState.Ai(),
        /** Saved agenda views list — refreshed from DB on each observation. */
        val agendaEphemeral: EphemeralState.Agenda = EphemeralState.Agenda(),

        // ── Error state ─────────────────────────────────────────────────────────
        /** Shown as a snackbar; cleared on next successful action. */
        val errorMessage: String? = null,
    ) : SettingsUiState
}
