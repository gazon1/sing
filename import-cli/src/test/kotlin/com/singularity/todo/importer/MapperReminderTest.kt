package com.singularity.todo.importer

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapperReminderTest {
    @Test
    fun `recurrence template notifies are mapped when a concrete start exists`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-template",
                                    title = "Template",
                                    start = "2028-11-08T17:00:00.000Z",
                                    recurrence = LegacyRecurrence(notifies = listOf(60, 0)),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                            ),
                    ),
            )

        val result = Mapper.map(backup, markCompleted = false)
        assertEquals(2, result.payload.taskReminders.size)
        assertTrue(result.payload.taskReminders.all { it.taskId == "T-template" })
        assertEquals(2, result.skipped["reminderSourceRecords"])
        assertEquals(2, result.skipped["remindersImported"])
        assertEquals(0, result.skipped["remindersDuplicatesMerged"])
        assertEquals(0, result.skipped["remindersMissingBaseTime"])
    }

    @Test
    fun `task and recurrence notifies are merged and duplicate offsets counted once`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-merge",
                                    title = "Merged",
                                    start = "2025-01-01T10:00:00.000Z",
                                    notifies = listOf(30, 60),
                                    recurrence = LegacyRecurrence(notifies = listOf(60, 120)),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                            ),
                    ),
            )

        val result = Mapper.map(backup, markCompleted = false)
        val reminders = result.payload.taskReminders
        val base = Mapper.parseInstantMs("2025-01-01T10:00:00.000Z")!!

        assertEquals(3, reminders.size, "both sources imported with same offset merged")
        assertEquals(setOf(-30, -60, -120), reminders.map { it.offsetMinutes }.toSet())
        assertEquals(base - 120 * 60_000L, reminders.single { it.offsetMinutes == -120 }.fireAt)
        assertEquals(4, result.skipped["reminderSourceRecords"])
        assertEquals(3, result.skipped["remindersImported"])
        assertEquals(1, result.skipped["remindersDuplicatesMerged"])
        assertEquals(0, result.skipped["remindersMissingBaseTime"])
    }

    @Test
    fun `date-only start uses deterministic utc midnight base for reminder fire time`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-date",
                                    title = "Date only",
                                    start = "2025-04-15",
                                    notifies = listOf(0, 60),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                            ),
                    ),
            )

        val result = Mapper.map(backup, markCompleted = false)
        val base = LocalDate.parse("2025-04-15").atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val byOffset = result.payload.taskReminders.associateBy { it.offsetMinutes }

        assertEquals(base, byOffset.getValue(0).fireAt)
        assertEquals(base - 60 * 60_000L, byOffset.getValue(-60).fireAt)
        assertEquals(2, result.skipped["remindersImported"])
        assertEquals(0, result.skipped["remindersMissingBaseTime"])
    }

    @Test
    fun `absolute start instants with timezone offsets keep reminder fire time`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-z",
                                    title = "Z",
                                    start = "2025-04-15T15:00:00Z",
                                    notifies = listOf(30),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                                LegacyTask(
                                    id = "T-plus",
                                    title = "Plus",
                                    start = "2025-04-15T15:00:00+02:00",
                                    notifies = listOf(30),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                                LegacyTask(
                                    id = "T-minus",
                                    title = "Minus",
                                    start = "2025-04-15T15:00:00-05:00",
                                    notifies = listOf(30),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                            ),
                    ),
            )

        val result = Mapper.map(backup, markCompleted = false)
        val byTask = result.payload.taskReminders.associateBy { it.taskId }
        assertEquals(Mapper.parseInstantMs("2025-04-15T15:00:00Z")!! - 30 * 60_000L, byTask.getValue("T-z").fireAt)
        assertEquals(
            Mapper.parseInstantMs("2025-04-15T15:00:00+02:00")!! - 30 * 60_000L,
            byTask.getValue("T-plus").fireAt,
        )
        assertEquals(
            Mapper.parseInstantMs("2025-04-15T15:00:00-05:00")!! - 30 * 60_000L,
            byTask.getValue("T-minus").fireAt,
        )
    }

    @Test
    fun `useTime false does not turn reminder base into task due time`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-notime",
                                    title = "No time",
                                    start = "2025-04-15T15:00:00.000Z",
                                    useTime = false,
                                    notifies = listOf(60),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                            ),
                    ),
            )

        val result = Mapper.map(backup, markCompleted = false)
        val task = result.payload.tasks.single()
        val reminder = result.payload.taskReminders.single()
        val base = Mapper.parseInstantMs("2025-04-15T15:00:00.000Z")!!

        assertEquals(null, task.dueTime, "useTime=false keeps task due time unset")
        assertEquals(base - 60 * 60_000L, reminder.fireAt, "reminder still fires relative to start instant")
    }

    @Test
    fun `invalid reminder start is reported as missing base time`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-bad",
                                    title = "Bad start",
                                    start = "not-a-date",
                                    notifies = listOf(5, 10),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                            ),
                    ),
            )

        val result = Mapper.map(backup, markCompleted = false)
        assertTrue(result.payload.taskReminders.isEmpty())
        assertEquals(2, result.skipped["reminderSourceRecords"])
        assertEquals(0, result.skipped["remindersImported"])
        assertEquals(0, result.skipped["remindersDuplicatesMerged"])
        assertEquals(2, result.skipped["remindersMissingBaseTime"])
    }

    @Test
    fun `missing reminder stamp is deterministic without current time`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-stamp",
                                    title = "No stamp",
                                    start = "2025-04-15T15:00:00.000Z",
                                    createdDate = null,
                                    modificatedDate = 0,
                                    notifies = listOf(10),
                                ),
                            ),
                    ),
            )

        val first =
            Mapper
                .map(backup, markCompleted = false)
                .payload.taskReminders
                .single()
        val second =
            Mapper
                .map(backup, markCompleted = false)
                .payload.taskReminders
                .single()

        assertEquals(first, second, "same source without timestamps produces identical reminder rows")
        assertEquals(0L, first.createdAt)
        assertEquals(0L, first.updatedAt)
    }

    @Test
    fun `mark-completed treats legacy checked as done regardless of state`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(id = "T-done-10", title = "Done", checked = 1, state = 0),
                                LegacyTask(id = "T-done-11", title = "Done dominant", checked = 1, state = 1),
                                LegacyTask(id = "T-open-01", title = "Open", checked = 0, state = 1),
                                LegacyTask(id = "T-odd-21", title = "Odd checked value", checked = 2, state = 1),
                            ),
                    ),
            )

        val mapped =
            Mapper
                .map(backup, markCompleted = true)
                .payload.tasks
                .associateBy { it.id }
        assertTrue(mapped.getValue("T-done-10").completedAt != null, "checked==1,state==0 is completed")
        assertTrue(mapped.getValue("T-done-11").completedAt != null, "checked==1,state==1 is completed")
        assertNull(mapped.getValue("T-open-01").completedAt, "checked==0 is not completed")
        assertNull(mapped.getValue("T-odd-21").completedAt, "checked!=1 is not completed")
    }

    @Test
    fun `without mark-completed legacy checked tasks stay active`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks = listOf(LegacyTask(id = "T-done", title = "Done", checked = 1, state = 1)),
                    ),
            )

        val task =
            Mapper
                .map(backup, markCompleted = false)
                .payload.tasks
                .single()
        assertNull(task.completedAt, "completion is only applied when explicitly requested")
    }
}
