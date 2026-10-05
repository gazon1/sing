package com.singularity.todo.feature.auth

import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.log.Redaction

/**
 * Removes every local row belonging to one account, and nothing belonging to another.
 *
 * Used when an account is replaced, so that the next account does not see the previous
 * one's data — and, just as importantly, so that signing in does not silently destroy
 * what the outgoing account had not yet sent anywhere.
 *
 * ## Why this class exists rather than a dozen queries at the call site
 *
 * The deletes have an **order**, and the order is load-bearing rather than stylistic.
 * Four tables carry no `user_id` and are reached through their parent:
 * `task_tags` and `task_dependencies` and `checklist_items` through `tasks`,
 * `project_tag_groups` through `projects`. Deleting a parent first orphans its children,
 * and an orphaned row is neither the old account's (the parent it named is gone) nor the
 * new one's — it is unreachable data that a later sign-in can collide with.
 *
 * Keeping the order in one place means it cannot be got wrong by a caller that lists the
 * tables in a different order, which is the whole failure mode of "just delete the rows".
 *
 * ## Why the erase is not atomic, and why that is stated here
 *
 * The writes are separate statements, so a failure part-way leaves the database with some
 * of the owner's rows gone and some not. That is a real gap and it is fixed by the
 * unit-of-work decision recorded in
 * `2026-10-05-who-owns-a-row-and-the-patch-that-describes-it` — the transaction seam
 * does not exist in `commonMain` today. Until it does, the ordering below is the best
 * available guarantee: it can leave an account's data partly erased, but it cannot leave
 * an account's data under someone else's ownership, which is the failure that cannot be
 * undone by the user.
 *
 * @param database the Room database. Every delete is scoped by the id list the
 * [OwnerRowIdResolver] produced — never by a LIKE pattern over `user_id`.
 * @param log records what was removed, by count, with the owner redacted.
 */
class OwnerScopedEraser(private val database: AppDatabase, private val log: (String) -> Unit = {}) {

    /**
     * Deletes every row that [resolved] says belongs to [OwnerRowIds.ownerId].
     *
     * Takes the resolved ids rather than an owner, so the caller cannot erase a scope it
     * has not resolved first — and so a test can assert on the resolution without a
     * database write being anywhere in reach.
     *
     * @return the number of rows removed per table, for logging and for a test that needs
     * to distinguish "removed nothing" from "removed the wrong nothing".
     */
    suspend fun erase(resolved: OwnerRowIds): EraseCounts {
        val scopes = resolved.scopedUserIds
        require(scopes.isNotEmpty()) {
            "refusing to erase an owner with no resolved scope; resolve first"
        }

        val erase = database.ownerEraseDao()

        // ── Children first ──────────────────────────────────────────────────────
        // Each reaches its own parent; `project_tag_groups` is scoped through
        // `projects`, not `tasks`, and an EXISTS against the wrong parent deletes
        // nothing while reporting success.
        //
        // The parent ids are read BEFORE any row is deleted: once a task is gone there
        // is no query that can find the children that named it.
        val scope = database.ownerScopeDao()
        val taskIds = scope.listTaskIdsForOwner(scopes)
        val projectIds = scope.listProjectIdsForOwner(scopes)

        val tagRefs = if (taskIds.isEmpty()) 0 else erase.deleteTagRefs(taskIds, scopes)
        val dependencies = if (taskIds.isEmpty()) 0 else erase.deleteDependencies(taskIds, scopes)
        val checklists = if (taskIds.isEmpty()) 0 else erase.deleteChecklistItems(taskIds, scopes)
        val inherited = if (projectIds.isEmpty()) {
            0
        } else {
            erase.deleteInheritedTagGroups(projectIds, scopes)
        }

        // ── Parents ─────────────────────────────────────────────────────────────
        // These carry their own `user_id`, so the scope list alone selects them; the
        // id list above is only there to scope a child lookup.
        val notes = erase.deleteNotes(scopes)
        val projects = erase.deleteProjects(scopes)
        val tags = erase.deleteTags(scopes)
        val taskReminders = erase.deleteTaskReminders(scopes)
        val projectReminders = erase.deleteProjectReminders(scopes)
        val tagGroups = erase.deleteTagGroups(scopes)
        val tasks = erase.deleteTasks(scopes)

        // ── Sync state ──────────────────────────────────────────────────────────
        // The queue last: if it went first, a failure during the deletes would leave
        // patches whose rows no longer exist, and the server would be told about a
        // document the device has forgotten.
        val outbox = database.syncOutboxDao().deleteForOwner(resolved.ownerId)
        val deadLetter = database.syncDeadLetterDao().deleteForOwner(resolved.ownerId)
        val shadow = database.syncShadowDao().deleteForOwner(resolved.ownerId)

        val counts = EraseCounts(
            tasks = tasks,
            notes = notes,
            projects = projects,
            tags = tags,
            tagRefs = tagRefs,
            dependencies = dependencies,
            checklists = checklists,
            inheritedTagGroups = inherited,
            taskReminders = taskReminders,
            projectReminders = projectReminders,
            tagGroups = tagGroups,
            outbox = outbox,
            deadLetter = deadLetter,
            shadows = shadow,
        )
        log(
            "Erased local data for owner=${Redaction.redactEmail(resolved.ownerId)}: " +
                "tasks=${counts.tasks} notes=${counts.notes} projects=${counts.projects} " +
                "tags=${counts.tags} children=${counts.childRows}",
        )
        return counts
    }
}

/**
 * Rows removed per table by one [OwnerScopedEraser.erase].
 *
 * Carried rather than returned as `Unit` so a caller — or a test — can tell "there was
 * nothing to remove" from "the delete matched a different set than expected". Both look
 * identical from the outside otherwise, and only one of them is correct.
 */
data class EraseCounts(
    val tasks: Int = 0,
    val notes: Int = 0,
    val projects: Int = 0,
    val tags: Int = 0,
    val tagRefs: Int = 0,
    val dependencies: Int = 0,
    val checklists: Int = 0,
    val inheritedTagGroups: Int = 0,
    val taskReminders: Int = 0,
    val projectReminders: Int = 0,
    val tagGroups: Int = 0,
    val outbox: Int = 0,
    val deadLetter: Int = 0,
    val shadows: Int = 0,
) {
    /** Every row removed that hangs off a parent by id rather than carrying its own owner. */
    val childRows: Int get() = tagRefs + dependencies + checklists + inheritedTagGroups

    /** Whether anything at all was removed. */
    val isEmpty: Boolean
        get() = tasks + notes + projects + tags + childRows +
            taskReminders + projectReminders + tagGroups + outbox + deadLetter + shadows == 0

    override fun toString(): String = buildString {
        append("{")
        append("tasks=").append(tasks)
        append(", notes=").append(notes)
        append(", projects=").append(projects)
        append(", tags=").append(tags)
        append(", childRows=").append(childRows)
        append(", sync=").append(outbox + deadLetter + shadows)
        append("}")
    }
}
