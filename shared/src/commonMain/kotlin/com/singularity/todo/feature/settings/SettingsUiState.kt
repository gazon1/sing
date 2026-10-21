package com.singularity.todo.feature.settings

import androidx.compose.runtime.Immutable
import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.textfields.FieldOwnedText
import com.singularity.todo.core.ui.textfields.StaticText

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
@Immutable
sealed interface SettingsUiState {
    data class Content(
        // ── Typed contributor sections ─────────────────────────────────────────
        val appearance: SettingsSection.Appearance = SettingsSection.Appearance(),
        val notifications: SettingsSection.Notifications = SettingsSection.Notifications(),
        val workSchedule: SettingsSection.WorkSchedule = SettingsSection.WorkSchedule(),
        val greeting: SettingsSection.Greeting = SettingsSection.Greeting(),
        val ai: SettingsSection.Ai = SettingsSection.Ai(),
        val defaultAgendaView: SettingsSection.DefaultAgendaView = SettingsSection.DefaultAgendaView(),
        val tasks: SettingsSection.Tasks = SettingsSection.Tasks(),

        // ── Ephemeral state (not persisted) ────────────────────────────────────
        /** AI test result, fetched model list, fetch errors — UI-only state. */
        val aiEphemeral: EphemeralState.Ai = EphemeralState.Ai(),
        /** Saved agenda views list — refreshed from DB on each observation. */
        val agendaEphemeral: EphemeralState.Agenda = EphemeralState.Agenda(),
        /** Log export loading / result — UI-only state. */
        val logExportEphemeral: EphemeralState.LogExport = EphemeralState.LogExport(),

        // ── Error state ─────────────────────────────────────────────────────────
        /** Shown as a snackbar; cleared on next successful action. */
        val errorMessage: String? = null,
    ) : SettingsUiState {
        /**
         * The base URL as the field's source.
         *
         * The settings are loaded from disk, and the provider dropdown may replace the URL with
         * that provider's default — a value the field never produced. So the value is never
         * rewritten with one the field produced: the field's echo guard needs no lifting, and there
         * is no counter to read.
         *
         * The promise is in the type of the value, not in this sentence: the base URL is a
         * [FieldOwnedText], and every write to it is named — the field's own text, a seed, or a
         * clear.
         */
        val baseUrlSource: StaticText get() = StaticText(baseUrl.text)

        /**
         * The model name as the field's source; see [baseUrlSource] for why no counter is needed.
         */
        val modelSource: StaticText get() = StaticText(model.text)

        /**
         * The system prompt as the field's source; see [baseUrlSource] for why no counter is
         * needed.
         */
        val systemPromptSource: StaticText get() = StaticText(systemPrompt.text)

        /**
         * The base URL as the field's own text.
         *
         * The value is the section's, and the section is the one that holds it — but the field
         * sees it through this property, so this is where the field's kind is written down.
         */
        val baseUrl: FieldOwnedText get() = ai.baseUrl

        /** The model name as the field's own text; see [baseUrl]. */
        val model: FieldOwnedText get() = ai.model

        /** The system prompt as the field's own text; see [baseUrl]. */
        val systemPrompt: FieldOwnedText get() = ai.systemPrompt
    }
}
