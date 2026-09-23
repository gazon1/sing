package com.singularity.todo.feature.calendar_sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapEntity
import com.singularity.todo.feature.calendar_sync.domain.logic.CalendarEventMapper
import com.singularity.todo.feature.calendar_sync.domain.logic.SyncDiffMerge
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.model.SyncPlan
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager [CoroutineWorker] that runs one-way task → system calendar sync.
 *
 * Triggered by [CalendarSyncWorkScheduler] via the MR-0 WorkManager infrastructure.
 * Reads all active tasks + reminder map, computes a diff, and applies the plan.
 *
 * Sync is one-way: local Task → system Calendar. The system calendar is never
 * read back as a source of truth for task state.
 *
 * @param context Android context.
 * @param params  Worker parameters.
 */
class CalendarSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val taskRepo: TaskRepository by inject()
    private val reminderRepo: ReminderRepository by inject()
    private val calendarProvider: CalendarProviderPort by inject()
    private val syncRepo: CalendarSyncRepository by inject()
    private val taskMapDao: CalendarSyncTaskMapDao by inject()

    override suspend fun doWork(): Result {
        // Check if sync is enabled
        if (!syncRepo.observeEnabled().first()) {
            return Result.success()
        }

        val targetCalendarId = syncRepo.observeTargetCalendarId().first()
        if (targetCalendarId == null) {
            syncRepo.setStatus(CalendarSyncStatus.Failed("No calendar selected"))
            return Result.success()
        }

        syncRepo.setStatus(CalendarSyncStatus.Syncing)

        return try {
            val allTasks = taskRepo.observeAll()
                .first()
                .filter { !it.isCompleted && !it.isTrashed }

            val existingMap: Map<String, Long> = taskMapDao.getAll()
                .associate { it.taskId to it.eventId }

            // Map all tasks to CalendarSyncEvents
            val desiredEvents = allTasks.mapNotNull { task ->
                val reminder = reminderRepo.watchByTask(task.id).first().firstOrNull()
                CalendarEventMapper.mapToEvent(task, reminder, targetCalendarId, existingMap[task.id.value])
            }

            val plans = SyncDiffMerge.diff(existingMap, desiredEvents)

            var inserted = 0
            var updated = 0
            var deleted = 0
            var errors = 0

            for (plan in plans) {
                when (plan) {
                    is SyncPlan.NoOp -> { /* nothing */ }

                    is SyncPlan.Insert -> {
                        calendarProvider.insertEvent(plan.event).onSuccess { eventId ->
                            taskMapDao.upsert(
                                CalendarSyncTaskMapEntity(
                                    taskId = plan.event.taskId.value,
                                    calendarId = targetCalendarId,
                                    eventId = eventId,
                                    syncedAt = System.currentTimeMillis(),
                                ),
                            )
                            inserted++
                        }.onFailure { errors++ }
                    }

                    is SyncPlan.Update -> {
                        calendarProvider.updateEvent(plan.eventId, plan.event).onSuccess {
                            taskMapDao.upsert(
                                CalendarSyncTaskMapEntity(
                                    taskId = plan.event.taskId.value,
                                    calendarId = targetCalendarId,
                                    eventId = plan.eventId,
                                    syncedAt = System.currentTimeMillis(),
                                ),
                            )
                            updated++
                        }.onFailure { errors++ }
                    }

                    is SyncPlan.Delete -> {
                        calendarProvider.deleteEvent(plan.eventId).onSuccess {
                            taskMapDao.deleteByEventId(plan.eventId)
                            deleted++
                        }.onFailure { errors++ }
                    }
                }
            }

            // Clean up stale mappings for tasks that no longer exist locally
            val currentTaskIds = allTasks.map { it.id.value }
            taskMapDao.deleteStale(currentTaskIds)

            syncRepo.setLastSyncedAt(System.currentTimeMillis())
            syncRepo.setStatus(
                if (errors == 0) {
                    CalendarSyncStatus.Idle(System.currentTimeMillis())
                } else {
                    CalendarSyncStatus.Failed("$errors operation(s) failed")
                },
            )

            if (errors > 0) Result.retry() else Result.success()
        } catch (e: Exception) {
            syncRepo.setStatus(CalendarSyncStatus.Failed(e.message ?: "Unknown error"))
            Result.retry()
        }
    }
}
