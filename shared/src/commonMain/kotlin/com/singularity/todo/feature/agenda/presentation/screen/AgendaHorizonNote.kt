package com.singularity.todo.feature.agenda.presentation.screen

import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition

/**
 * The line the Upcoming tab ends with: where a task dated beyond next week is seen.
 *
 * The three bottom-bar tabs cover a **horizon** — Inbox the undated tasks, Today the
 * overdue and today's ones, Upcoming tomorrow through next week — so a task due in
 * three weeks is in none of them. It is not lost: the Plans timeline, the Calendar and
 * a saved view seeded from [AgendaPresets.All] all hold it. That is a stated decision
 * (see ADR `2026-11-05-the-agenda-tabs-cover-a-horizon`), and this is the sentence
 * that states it to the user, at the end of the tab whose promise stops at next week.
 *
 * The wording names the two surfaces by the names the app uses for them and avoids the
 * word "horizon", which is ours, not the user's.
 */
object AgendaHorizonNote {
    /** The pointer, as the user reads it. */
    const val TEXT: String =
        "Planning further ahead? Tasks due after next week are in the Calendar " +
            "and on the Plans timeline."

    /**
     * The line [definition]'s screen shows, or `null` when that tab has no horizon.
     *
     * Only [AgendaPresets.Upcoming] carries it: Inbox and Today do not stop at a
     * horizon, and a saved view, a project list and a tag list are not tabs.
     */
    fun forDefinition(definition: AgendaDefinition): String? =
        TEXT.takeIf { definition.title == AgendaPresets.Upcoming.title }
}
