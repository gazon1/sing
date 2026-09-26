package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncEvent
import com.singularity.todo.feature.calendar_sync.domain.model.SyncPlan
import com.singularity.todo.feature.calendar_sync.domain.model.SyncedEventRef
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncDiffMergeTest {

    private fun entity(taskId: String, calendarId: String = "cal1", eventId: Long = 100L, checksum: Int = 0) =
        SyncedEventRef(
            taskId = taskId,
            calendarId = calendarId,
            eventId = eventId,
            checksum = checksum,
        )

    private fun event(taskId: String, calendarId: String = "cal1", checksum: Int = 1) = CalendarSyncEvent(
        taskId = TaskId(taskId),
        calendarId = calendarId,
        eventId = null,
        title = "Task $taskId",
        description = "desc",
        startMs = 1000L,
        endMs = 2000L,
        allDay = false,
        rrule = null,
        color = null,
        checksum = checksum,
    )

    @Test
    fun empty_existing_empty_desired_returns_empty() {
        val plans = SyncDiffMerge.diff(emptyMap(), emptyList())
        assertEquals(emptyList(), plans)
    }

    @Test
    fun empty_existing_with_desired_returns_insert() {
        val e = event("t1")
        val plans = SyncDiffMerge.diff(emptyMap(), listOf(e))
        assertEquals(1, plans.size)
        assertEquals(SyncPlan.Insert(e), plans[0])
    }

    @Test
    fun existing_with_same_checksum_returns_noop() {
        val e = event("t1", checksum = 42)
        val existing = entity("t1", checksum = 42)
        val plans = SyncDiffMerge.diff(mapOf("t1" to existing), listOf(e))
        assertEquals(1, plans.size)
        assertEquals(SyncPlan.NoOp, plans[0])
    }

    @Test
    fun existing_with_different_checksum_returns_update() {
        val e = event("t1", checksum = 99)
        val existing = entity("t1", checksum = 42)
        val plans = SyncDiffMerge.diff(mapOf("t1" to existing), listOf(e))
        assertEquals(1, plans.size)
        assertEquals(SyncPlan.Update(100L, e), plans[0])
    }

    @Test
    fun existing_with_different_calendarid_returns_delete_plus_insert() {
        // Moving from calendar A to calendar B: can't update in-place, must delete + insert
        val e = event("t1", calendarId = "cal2", checksum = 99)
        val existing = entity("t1", calendarId = "cal1", eventId = 100L, checksum = 42)
        val plans = SyncDiffMerge.diff(mapOf("t1" to existing), listOf(e))
        assertEquals(2, plans.size)
        assertEquals(SyncPlan.Delete(100L), plans[0])
        assertEquals(SyncPlan.Insert(e), plans[1])
    }

    @Test
    fun existing_task_not_in_desired_returns_delete() {
        val existing = entity("t1")
        val plans = SyncDiffMerge.diff(mapOf("t1" to existing), emptyList())
        assertEquals(1, plans.size)
        assertEquals(SyncPlan.Delete(100L), plans[0])
    }

    @Test
    fun mixed_operations_returns_all_plans() {
        // t1: same checksum → NoOp
        // t2: diff checksum → Update
        // t3: new → Insert
        // t4: not in desired → Delete
        val e1 = event("t1", checksum = 1)
        val e2 = event("t2", checksum = 99)
        val e3 = event("t3", checksum = 3)
        val ex1 = entity("t1", checksum = 1)
        val ex2 = entity("t2", checksum = 2)
        val ex4 = entity("t4", checksum = 4)
        val existing = mapOf("t1" to ex1, "t2" to ex2, "t4" to ex4)
        val desired = listOf(e1, e2, e3)
        val plans = SyncDiffMerge.diff(existing, desired)
        assertEquals(4, plans.size)
        assertEquals(SyncPlan.NoOp, plans[0])
        assertEquals(SyncPlan.Update(100L, e2), plans[1])
        assertEquals(SyncPlan.Insert(e3), plans[2])
        assertEquals(SyncPlan.Delete(100L), plans[3])
    }

    @Test
    fun same_taskId_same_calendar_different_content_returns_update() {
        // Same taskId + same calendarId + different content → Update (not Delete+Insert)
        val e = event("t1", checksum = 99)
        val existing = entity("t1", calendarId = "cal1", eventId = 100L, checksum = 1)
        val plans = SyncDiffMerge.diff(mapOf("t1" to existing), listOf(e))
        assertEquals(1, plans.size)
        assertEquals(SyncPlan.Update(100L, e), plans[0])
    }
}
