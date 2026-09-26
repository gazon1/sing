package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent
import com.singularity.todo.feature.calendar_sync.domain.model.SyncPlan
import com.singularity.todo.feature.calendar_sync.domain.model.SyncedEventRef

/**
 * Pure diff: compares a map of existing (taskId → SyncedEventRef) against
 * the current desired state (List<CalendarSyncEvent>) and produces a list of [SyncPlan] operations.
 *
 * One-way merge: Task → system calendar only. No reverse sync.
 *
 * @param existingMap Map of taskId.value → [SyncedEventRef] (includes eventId, calendarId, checksum).
 *                    Entries without a system event ID are treated as "not synced yet".
 * @param desiredEvents The events we want reflected in the system calendar.
 */
object SyncDiffMerge {

    /**
     * Computes the minimal sync plan to bring the system calendar in sync.
     *
     * Algorithm:
     * 1. For each desired event:
     *    - No existing entry → Insert
     *    - Existing entry with different calendarId → Delete(oldEventId) + Insert(new)  (moved calendar)
     *    - Existing entry with same calendarId but changed content (checksum differs) → Update
     *    - Existing entry with identical content (same checksum) → NoOp
     * 2. For each existing taskId not in desiredEvents:
     *    - Has eventId → Delete
     *    - No eventId → NoOp (already absent)
     */
    fun diff(existingMap: Map<String, SyncedEventRef>, desiredEvents: List<CalendarSyncEvent>): List<SyncPlan> {
        val ops = mutableListOf<SyncPlan>()
        val seenTaskIds = mutableSetOf<String>()

        for (event in desiredEvents) {
            val taskKey = event.taskId.value
            seenTaskIds.add(taskKey)
            val existing = existingMap[taskKey]

            when {
                // Not yet synced — insert
                existing == null -> {
                    ops.add(SyncPlan.Insert(event))
                }

                // Calendar changed — Delete + Insert (can't move via Update)
                existing.calendarId != event.calendarId -> {
                    ops.add(SyncPlan.Delete(existing.eventId))
                    ops.add(SyncPlan.Insert(event))
                }

                // Same calendar, content changed — Update
                event.checksum != existing.checksum -> {
                    ops.add(SyncPlan.Update(existing.eventId, event))
                }

                // Identical — no-op
                else -> {
                    ops.add(SyncPlan.NoOp)
                }
            }
        }

        // Tasks that existed in the map but are no longer desired → delete
        for ((taskKey, existing) in existingMap) {
            if (taskKey !in seenTaskIds) {
                ops.add(SyncPlan.Delete(existing.eventId))
            }
        }

        return ops
    }
}

/**
 * Builds a stable hash from the fields that affect how an event displays in a calendar.
 * Changes to any of these fields produce a different hash → triggers a re-write.
 */
fun CalendarSyncEvent.checksum(): Int = listOfNotNull(
    title,
    description,
    startMs,
    endMs,
    if (allDay) 1 else 0,
    rrule,
    calendarId,
).hashCode()
