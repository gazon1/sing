package com.singularity.todo.feature.auth

import com.singularity.todo.core.database.AppDatabase

/**
 * Computes both [ReassignCounts] and [EraseCounts] for an owner's local data in a single
 * pass over the database.
 *
 * Both counts are computed together because they share the same base-table queries — the only
 * difference is that [EraseCounts] also includes child rows (tag refs, dependencies,
 * checklists, inherited tag groups), which require reading parent ids first.
 *
 * Computing them together means:
 * - One resolved `OwnerRowIds` lookup instead of two
 * - One set of parent-id reads (`taskIds`, `projectIds`) instead of two
 * - Identical base-table counts obtained once and reused
 *
 * @param database the Room database, providing access to all DAOs this class needs.
 */
class OwnerCountsCalculator(private val database: AppDatabase) {

    /**
     * What an owner holds, as both the reassign option and the erase option.
     *
     * Returned by [compute] so a single call supplies both counts a screen needs
     * before the user has chosen.
     */
    data class CountsPair(val reassign: ReassignCounts, val erase: EraseCounts)

    /**
     * Counts what [resolved] holds, in both the transfer and erase units.
     *
     * Identical structure to [OwnerScopedEraser.preview] and
     * [OwnerScopedReassigner.summarise] combined into one pass: same reads, same ordering,
     * same parent-id-first strategy for child tables.
     */
    suspend fun compute(resolved: OwnerRowIds): CountsPair {
        val scopes = resolved.scopedUserIds
        if (scopes.isEmpty()) {
            return CountsPair(ReassignCounts(), EraseCounts())
        }

        val scope = database.ownerScopeDao()
        val reassignDao = database.ownerReassignDao()
        val eraseDao = database.ownerEraseDao()
        val taskIds = scope.listTaskIdsForOwner(scopes)
        val projectIds = scope.listProjectIdsForOwner(scopes)

        // ── Base table counts (identical in both reassign and erase) ────────────
        val tasks = reassignDao.countTasks(scopes)
        val notes = reassignDao.countNotes(scopes)
        val projects = reassignDao.countProjects(scopes)
        val tags = reassignDao.countTags(scopes)
        val tagGroups = reassignDao.countTagGroups(scopes)
        val taskReminders = reassignDao.countTaskReminders(scopes)
        val projectReminders = reassignDao.countProjectReminders(scopes)
        val timeEntries = reassignDao.countTimeEntries(scopes)
        val agendaViews = reassignDao.countAgendaViews(scopes)

        // ── Reassign-specific ──────────────────────────────────────────────────
        val profiles = reassignDao.countProfiles(resolved.ownerId)
        val outbox = reassignDao.countOutbox(resolved.ownerId)
        val deadLetter = reassignDao.countDeadLetter(resolved.ownerId)
        val shadows = reassignDao.countShadows(resolved.ownerId)

        val reassignCounts = ReassignCounts(
            tasks = tasks,
            notes = notes,
            projects = projects,
            tags = tags,
            tagGroups = tagGroups,
            taskReminders = taskReminders,
            projectReminders = projectReminders,
            timeEntries = timeEntries,
            agendaViews = agendaViews,
            profiles = profiles,
            outbox = outbox,
            deadLetter = deadLetter,
            shadows = shadows,
        )

        // ── Erase-specific: child rows (requires parent ids) ─────────────────
        val tagRefs = if (taskIds.isEmpty()) 0 else eraseDao.countTagRefs(taskIds, scopes)
        val dependencies = if (taskIds.isEmpty()) 0 else eraseDao.countDependencies(taskIds, scopes)
        val checklists = if (taskIds.isEmpty()) 0 else eraseDao.countChecklistItems(taskIds, scopes)
        val inherited = if (projectIds.isEmpty()) {
            0
        } else {
            eraseDao.countInheritedTagGroups(projectIds, scopes)
        }

        val outboxSync = database.syncOutboxDao().countForOwner(resolved.ownerId)
        val deadLetterSync = database.syncDeadLetterDao().countForOwner(resolved.ownerId)
        val shadowSync = database.syncShadowDao().countForOwner(resolved.ownerId)

        val eraseCounts = EraseCounts(
            tasks = tasks,
            notes = notes,
            projects = projects,
            tags = tags,
            tagGroups = tagGroups,
            taskReminders = taskReminders,
            projectReminders = projectReminders,
            timeEntries = timeEntries,
            agendaViews = agendaViews,
            outbox = outboxSync,
            deadLetter = deadLetterSync,
            shadows = shadowSync,
            tagRefs = tagRefs,
            dependencies = dependencies,
            checklists = checklists,
            inheritedTagGroups = inherited,
        )

        return CountsPair(reassignCounts, eraseCounts)
    }
}
