package com.singularity.todo.feature.calendar_sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapDao
import com.singularity.todo.feature.calendar_sync.data.CalendarSyncTaskMapEntity
import com.singularity.todo.feature.calendar_sync.domain.logic.CalendarEventMapper
import com.singularity.todo.feature.calendar_sync.domain.logic.SyncDiffMerge
import com.singularity.todo.feature.calendar_sync.domain.logic.checksum
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.model.SyncPlan
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.error.CalendarSyncException
import com.singularity.todo.feature.calendar_sync.error.FailureType
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
 * All [CalendarSyncException] subtypes thrown by [CalendarProviderPort] operations
 * are caught and translated into [CalendarSyncStatus.Failed] with a [FailureType],
 * giving the UI enough context to show tailored recovery actions.
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

            // Use full entity map so diff can detect calendarId changes
            val existingMap: Map<String, CalendarSyncTaskMapEntity> = taskMapDao.getAll()
                .associateBy { it.taskId }

            // Map all tasks to CalendarSyncEvents
            val desiredEvents = allTasks.mapNotNull { task ->
                val reminder = reminderRepo.watchByTask(task.id).first().firstOrNull()
                val existingEntity = existingMap[task.id.value]
                CalendarEventMapper.mapToEvent(
                    task,
                    reminder,
                    targetCalendarId,
                    existingEntity?.eventId,
                )
            }

            val plans = SyncDiffMerge.diff(existingMap, desiredEvents)

            var inserted = 0
            var updated = 0
            var deleted = 0
            var errors = 0
            val failedDeletes = mutableSetOf<Long>() // eventIds whose Delete failed

            for (plan in plans) {
                when (plan) {
                    is SyncPlan.NoOp -> { /* nothing */ }

                    is SyncPlan.Insert -> {
                        calendarProvider.insertEvent(plan.event).onSuccess { eventId ->
                            taskMapDao.upsert(
                                CalendarSyncTaskMapEntity(
                                    taskId = plan.event.taskId.value,
                                    calendarId = plan.event.calendarId,
                                    eventId = eventId,
                                    syncedAt = System.currentTimeMillis(),
                                    checksum = plan.event.checksum(),
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
                                    calendarId = plan.event.calendarId,
                                    eventId = plan.eventId,
                                    syncedAt = System.currentTimeMillis(),
                                    checksum = plan.event.checksum(),
                                ),
                            )
                            updated++
                        }.onFailure { errors++ }
                    }

                    is SyncPlan.Delete -> {
                        calendarProvider.deleteEvent(plan.eventId).onSuccess {
                            taskMapDao.deleteByEventId(plan.eventId)
                            deleted++
                        }.onFailure {
                            failedDeletes.add(plan.eventId)
                            errors++
                        }
                    }
                }
            }

            // Standard stale cleanup: remove mappings for tasks that no longer exist locally
            val currentTaskIds = allTasks.map { it.id.value }
            taskMapDao.deleteStale(currentTaskIds)

            syncRepo.setLastSyncedAt(System.currentTimeMillis())
            syncRepo.setStatus(
                if (errors == 0) {
                    CalendarSyncStatus.Idle(System.currentTimeMillis())
                } else {
                    CalendarSyncStatus.Failed("$errors operation(s) failed", FailureType.Transient)
                },
            )

            if (errors > 0) Result.retry() else Result.success()

        } catch (e: CalendarSyncException) {
            val type = when (e) {
                is CalendarSyncException.PermissionRevokedException -> FailureType.PermissionRevoked
                is CalendarSyncException.CalendarNotFoundException -> FailureType.CalendarNotFound
                is CalendarSyncException.CalendarAppMissingException -> FailureType.CalendarAppMissing
                is CalendarSyncException.TransientSyncException -> FailureType.Transient
                is CalendarSyncException.NetworkSyncException -> FailureType.Network
            }
            syncRepo.setStatus(CalendarSyncStatus.Failed(e.message ?: "Sync failed", type))
            // PermissionRevoked and CalendarAppMissing are not retryable — user must take action
            if (type == FailureType.PermissionRevoked || type == FailureType.CalendarAppMissing) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            syncRepo.setStatus(CalendarSyncStatus.Failed(e.message ?: "Unknown error", FailureType.Unknown))
            Result.retry()
        }
    }
}
