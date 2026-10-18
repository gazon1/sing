package com.singularity.todo.feature.auth

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.log.Redaction

/**
 * Moves every local row from one owner to another, and nothing belonging to a third.
 *
 * The counterpart to [OwnerScopedEraser]: where that answers "which rows would vanish",
 * this answers "which rows change hands", which is what an offline user choosing to
 * attach an account needs.
 *
 * ## Why the outbox moves too, and why that is the load-bearing part
 *
 * The data rows are the obvious half. The queued patches are the half that decides
 * whether the operation achieves anything at all. A task created offline was queued
 * under the local owner even though the push was cancelled — the write pipeline queues
 * regardless of whether a session may send it — so after a transfer that moved only the
 * data the account would hold the task locally and the server would never hear about
 * it. Nothing would report an error: the push simply finds nothing for that owner.
 *
 * ## Why profiles move last
 *
 * A profile row is what resolves an owner to a set of scopes. Flipping `profiles.user_id`
 * first would make a half-moved set visible to the new owner — some of the user's tasks
 * present, the rest missing, with no indication which. Left until last, the new owner
 * resolves nothing until the move is complete, so a failure part-way leaves the rows
 * where they were rather than showing up as a partially imported account.
 *
 * ## Why this is not atomic, and why that is stated here rather than worked around
 *
 * The statements are separate; there is no transaction seam in `commonMain` today (the
 * same gap [OwnerScopedEraser] documents). A failure part-way leaves some rows under the
 * old owner and some under the new one. The ordering bounds the damage — a partially
 * moved set is invisible to both owners rather than visible to one — but it does not
 * remove it, and the caller is expected to leave the session local on failure so the
 * user still holds everything they had. See `LocalAccountClaimer`.
 *
 * @param database the Room database. Every update is an exact `(from, to)` equality
 * match on `user_id` — never a pattern.
 * @param log records what moved, by count, with both owners redacted.
 */
class OwnerScopedReassigner(private val database: AppDatabase, private val log: (String) -> Unit = {}) {

    /**
     * Re-files every scope in [mapping] onto its counterpart.
     *
     * Takes the mapping rather than two owners, so a caller cannot invent a pairing that
     * the resolver did not compose.
     *
     * @return rows moved per table, so a caller can tell "moved nothing" from "moved the
     * wrong nothing" — both look identical from the outside and only one is right.
     */
    suspend fun reassign(mapping: OwnerIdMapping): ReassignCounts {
        require(!mapping.isEmpty) { "refusing to reassign an owner with no resolved scope; resolve first" }

        val dao = database.ownerReassignDao()

        // ── Data rows, one scope pair at a time ──────────────────────────────────
        //
        // Accumulated across pairs rather than overwritten: the default profile's
        // scope and each other profile's are separate statements, and the count is
        // the whole total, not the last pair's share.
        var tasks = 0
        var notes = 0
        var projects = 0
        var tags = 0
        var tagGroups = 0
        var taskReminders = 0
        var projectReminders = 0
        var timeEntries = 0
        var agendaViews = 0

        for ((from, to) in mapping.pairs) {
            tasks += dao.reassignTasks(from, to)
            notes += dao.reassignNotes(from, to)
            projects += dao.reassignProjects(from, to)
            tags += dao.reassignTags(from, to)
            tagGroups += dao.reassignTagGroups(from, to)
            taskReminders += dao.reassignTaskReminders(from, to)
            projectReminders += dao.reassignProjectReminders(from, to)
            timeEntries += dao.reassignTimeEntries(from, to)
            agendaViews += dao.reassignAgendaViews(from, to)
        }

        // ── Sync state, keyed by the bare owner ─────────────────────────────────
        val outbox = dao.reassignOutbox(mapping.fromOwnerId, mapping.toOwnerId)
        val deadLetter = dao.reassignDeadLetter(mapping.fromOwnerId, mapping.toOwnerId)
        val shadows = dao.reassignShadows(mapping.fromOwnerId, mapping.toOwnerId)

        // ── Ownership, last ─────────────────────────────────────────────────────
        val profiles = dao.reassignProfiles(mapping.fromOwnerId, mapping.toOwnerId)

        val counts = ReassignCounts(
            tasks = tasks,
            notes = notes,
            projects = projects,
            tags = tags,
            taskReminders = taskReminders,
            projectReminders = projectReminders,
            tagGroups = tagGroups,
            timeEntries = timeEntries,
            agendaViews = agendaViews,
            profiles = profiles,
            outbox = outbox,
            deadLetter = deadLetter,
            shadows = shadows,
        )
        log(
            "Moved local data ${Redaction.redactEmail(mapping.fromOwnerId)} → " +
                "${Redaction.redactEmail(mapping.toOwnerId)}: $counts",
        )
        return counts
    }
}

/**
 * Rows moved per table by one [OwnerScopedReassigner.reassign].
 *
 * Same shape of answer as [EraseCounts] and for the same reason: "there was nothing to
 * move" and "it moved the wrong set" are indistinguishable from the outside.
 *
 * Implements [OwnerOperationCounts] so that the 12 shared counters are declared in
 * one place; the `profiles` counter and [isEmpty] formula are reassign-specific.
 */
data class ReassignCounts(
    // ── 12 shared counters (OwnerOperationCounts) ────────────────────────────
    override val tasks: Int = 0,
    override val notes: Int = 0,
    override val projects: Int = 0,
    override val tags: Int = 0,
    override val tagGroups: Int = 0,
    override val taskReminders: Int = 0,
    override val projectReminders: Int = 0,
    override val timeEntries: Int = 0,
    override val agendaViews: Int = 0,
    override val outbox: Int = 0,
    override val deadLetter: Int = 0,
    override val shadows: Int = 0,
    // ── Reassign-specific ───────────────────────────────────────────────────
    val profiles: Int = 0,
) : OwnerOperationCounts {
    /** Whether anything at all changed hands. */
    override val isEmpty: Boolean
        get() = tasks + notes + projects + tags + tagGroups +
            taskReminders + projectReminders + timeEntries + agendaViews +
            profiles + outbox + deadLetter + shadows == 0

    override fun toString(): String = buildString {
        append("{")
        append("tasks=").append(tasks)
        append(", notes=").append(notes)
        append(", projects=").append(projects)
        append(", tags=").append(tags)
        append(", timeEntries=").append(timeEntries)
        append(", agendaViews=").append(agendaViews)
        append(", profiles=").append(profiles)
        append(", sync=").append(syncTotal)
        append("}")
    }
}

/**
 * What an owner holds, in the two units a person can reason about.
 *
 * Task and note counts only: the other tables are reached through them and counting
 * them separately would put a number on the screen that means nothing to the person
 * reading it.
 */
data class LocalDataSummary(val tasks: Int, val notes: Int) {
    /** Whether there is anything at stake in the choice. */
    val isEmpty: Boolean get() = tasks == 0 && notes == 0

    /** "3 tasks, 1 note" / "1 task" / "nothing yet" — one wording per singular and plural. */
    val describe: String
        get() = when {
            isEmpty -> "Nothing yet"
            notes == 0 -> "$tasks ${tasks.plural("task", "tasks")}"
            tasks == 0 -> "$notes ${notes.plural("note", "notes")}"
            else -> "$tasks ${tasks.plural("task", "tasks")} and $notes ${notes.plural("note", "notes")}"
        }
}

/** "1 task" but "3 tasks" — the only place in this feature that guesses a noun's number. */
private fun Int.plural(one: String, many: String): String = if (this == 1) one else many
