package com.singularity.todo.feature.calendar_sync.sync

import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first

/**
 * Computes a stable hash of the current sync-relevant state.
 *
 * The hash is used by [CalendarSyncOrchestrator] to skip redundant sync requests:
 * if the hash hasn't changed since the last handoff to WorkManager, the orchestrator
 * skips scheduling a new job.
 *
 * Hash inputs (all at minute granularity to avoid sub-minute flapping):
 * - `enabled`, `targetCalendarId`, `targetAppPackage` from [CalendarSyncRepository]
 * - For every non-completed, non-trashed task:
 *   `task.id.value`, `task.title`, `task.description`, `task.dueDate`, `task.dueTime`,
 *   `task.isCompleted`, `task.isTrashed`, `task.accentColor`
 * - `System.currentTimeMillis() / 60_000` (minute slot)
 *
 * @param taskRepo Source of all active tasks.
 * @param syncRepo Source of sync settings (enabled, calendarId, appPackage).
 */
class DirtyHashProvider(private val taskRepo: TaskRepository, private val syncRepo: CalendarSyncRepository) {

    /**
     * Returns a stable integer hash for the current sync-relevant state.
     * Returns `0L` if sync is disabled (nothing to sync).
     */
    suspend fun hash(): Long {
        if (!syncRepo.observeEnabled().first()) return 0L

        val calendarId = syncRepo.observeTargetCalendarId().first() ?: return 0L
        val appPackage = syncRepo.observeTargetAppPackage().first()

        val tasks = taskRepo.observeAll()
            .first()
            .filter { !it.isCompleted && !it.isTrashed }

        var h = 17L
        h = h * 31L + calendarId.hashCode()
        h = h * 31L + appPackage.hashCode()
        h = h * 31L + (System.currentTimeMillis() / 60_000)

        for (task in tasks) {
            h = h * 31L + task.id.value.hashCode()
            h = h * 31L + task.title.hashCode()
            h = h * 31L + (task.description?.hashCode() ?: 0)
            h = h * 31L + task.dueDate.hashCode()
            h = h * 31L + task.dueTime.hashCode()
            h = h * 31L + task.isCompleted.hashCode()
            h = h * 31L + task.isTrashed.hashCode()
            h = h * 31L + (task.accentColor ?: 0L)
        }

        return h
    }
}
