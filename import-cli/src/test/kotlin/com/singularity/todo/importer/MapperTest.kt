package com.singularity.todo.importer

import com.singularity.todo.core.backup.BackupFormat
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The legacy rows as the app's own rows.
 *
 * Each test names one field the legacy file spells differently from our model:
 * a delete date that is an archive, an icon that is several code points, a
 * monthly rule that carries the day. The failure each one guards is silent —
 * the row lands, only wrong.
 */
class MapperTest {
    @Test
    fun `a project with a delete date is archived, not deleted`() {
        val result =
            Mapper.map(
                LegacyBackup(
                    data =
                        LegacyData(
                            projects =
                                listOf(
                                    LegacyProject(
                                        id = "P-1",
                                        title = "A project",
                                        deleteDate = "2024-09-14T09:36:24.183Z",
                                        showInBasket = true,
                                    ),
                                ),
                        ),
                ),
                markCompleted = false,
            )

        val project = result.payload.projects.single()
        assertEquals(
            Mapper.parseInstantMs("2024-09-14T09:36:24.183Z"),
            project.closedAt,
            "deleteDate is the archive date",
        )
        assertEquals(false, project.isDeleted, "an archived project is not in the trash")
        assertNull(project.deletedAt)
    }

    @Test
    fun `a task with a delete date is archived even without the basket flag`() {
        val result =
            Mapper.map(
                LegacyBackup(
                    data =
                        LegacyData(
                            tasks =
                                listOf(
                                    LegacyTask(
                                        id = "T-1",
                                        title = "A task",
                                        deleteDate = "2024-02-09T22:12:02.849Z",
                                        showInBasket = false,
                                    ),
                                ),
                        ),
                ),
                markCompleted = false,
            )

        assertEquals(
            Mapper.parseInstantMs("2024-02-09T22:12:02.849Z"),
            result.payload.tasks
                .single()
                .archivedAt,
            "the legacy deletion date is the archive time",
        )
    }

    @Test
    fun `a multi code point emoji survives whole`() {
        val result =
            Mapper.map(
                LegacyBackup(
                    data =
                        LegacyData(
                            projects =
                                listOf(
                                    LegacyProject(id = "P-1", title = "A project", emoji = "1f9d1-200d-1f4bb"),
                                ),
                        ),
                ),
                markCompleted = false,
            )

        val icon =
            result.payload.projects
                .single()
                .icon
        assertEquals(
            listOf("1f9d1", "200d", "1f4bb").map { String(Character.toChars(it.toInt(16))) }.joinToString(""),
            icon,
            "every code point becomes a character, including the joiner",
        )
        assertTrue(icon?.contains("-") == false, "the raw hex must not reach the icon field: $icon")
    }

    @Test
    fun `a legacy icon with nothing usable is no icon`() {
        assertNull(Mapper.emojiCodepointToIcon(""), "an empty value is no icon")
        assertNull(Mapper.emojiCodepointToIcon("   "), "blank is no icon")
        assertNull(Mapper.emojiCodepointToIcon("zzz"), "a part that is not hex leaves nothing")
    }

    @Test
    fun `a code point outside Unicode is dropped rather than written`() {
        // String(Character.toChars(0x110000)) throws; a legacy file must not take
        // the whole import down with it.
        assertNull(
            Mapper.emojiCodepointToIcon("110000"),
            "a value past U+10FFFF cannot become a character and must not be forced into one",
        )
    }

    @Test
    fun `a single code point becomes its character`() {
        assertEquals("🚀", Mapper.emojiCodepointToIcon("1f680"), "the base case the sequence builds on")
        assertEquals("a", Mapper.emojiCodepointToIcon("61"), "a BMP code point is one character too")
    }

    @Test
    fun `a monthly rule keeps its own day`() {
        val rule = recurrenceOf("EVERYMONTH", dayNumber = 20)

        val spec = StableJson.decodeFromString(RecurrenceSpec.serializer(), rule)
        assertEquals(20, (spec as RecurrenceSpec.Monthly).dayOfMonth, "the legacy rule names the day")
    }

    @Test
    fun `a yearly rule keeps its month and day`() {
        val rule = recurrenceOf("EVERYYEAR", dayNumber = 1, monthNumber = 11)

        val spec = StableJson.decodeFromString(RecurrenceSpec.serializer(), rule)
        val yearly = spec as RecurrenceSpec.Yearly
        assertEquals(11, yearly.month)
        assertEquals(1, yearly.day)
    }

    @Test
    fun `the payload carries the app's own schema version`() {
        val result = Mapper.map(LegacyBackup(), markCompleted = false)

        assertEquals(BackupFormat.SCHEMA_VERSION, result.payload.schemaVersion, "the literal is the bug")
    }

    @Test
    fun `a note body is html, and the task and project descriptions are its words`() {
        val delta = """[{"insert":"hello "},{"insert":"world","attributes":{"bold":true}},{"insert":"\n"}]"""
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks = listOf(LegacyTask(id = "T-1", title = "A task", note = "N-1")),
                        projects =
                            listOf(
                                LegacyProject(id = "P-1", title = "A project", note = "N-1"),
                                LegacyProject(id = "P-2", title = "A project with a missing note", note = "N-gone"),
                            ),
                        notes = listOf(LegacyNote(id = "N-1", containerId = "T-1", content = delta)),
                    ),
            )

        val payload = Mapper.map(backup, markCompleted = false).payload
        val note = payload.notes.single()
        assertEquals("<p>hello <strong>world</strong></p>", note.bodyHtml, "the editor renders HTML, not the document")
        assertEquals(2, note.wordCount, "the count is of words, not of JSON tokens")
        assertEquals("hello world", payload.tasks.single().description, "a description is words")
        assertEquals(
            "hello world",
            payload.projects.single { it.id == "P-1" }.description,
            "a project note is resolved, not an id",
        )
        assertEquals(
            null,
            payload.projects.single { it.id == "P-2" }.description,
            "an id whose body is gone is not a description",
        )
    }

    @Test
    fun `a subtask without a project belongs to its parent's project`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        projects = listOf(LegacyProject(id = "P-1", title = "A project")),
                        tasks =
                            listOf(
                                LegacyTask(id = "T-1", title = "Parent", projectId = "P-1"),
                                LegacyTask(id = "T-2", title = "Child", parent = "T-1"),
                            ),
                    ),
            )

        val child =
            Mapper
                .map(backup, markCompleted = false)
                .payload.tasks
                .single { it.id == "T-2" }

        assertEquals("P-1", child.projectId, "a subtask follows its parent into the project")
        assertEquals("T-1", child.parentTaskId, "and stays under the task it hangs off")
    }

    @Test
    fun `titles are words, not markup`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        projects = listOf(LegacyProject(id = "P-1", title = "ytb -&gt; notebooklm")),
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-1",
                                    title = """Доставка из <a href="divanboss.ru">divanboss.ru</a>""",
                                ),
                            ),
                    ),
            )

        val payload = Mapper.map(backup, markCompleted = false).payload

        assertEquals("ytb -> notebooklm", payload.projects.single().name, "an entity is a spelling, not the text")
        assertEquals("Доставка из divanboss.ru", payload.tasks.single().title, "a title carries no tags")
    }

    /**
     * The reminder records the legacy app carried on a task become native reminder
     * rows: each offset turns into a row for the right task, with the fire time
     * derived from the task start and the offset preserved. The 2026-11-08 backup
     * had 1,756 tasks with notifies and imported as zero rows because the mapper
     * never read them — a source record must now produce a row, not silence.
     */
    @Test
    fun `task notifies are mapped to reminders with preserved offset and fire time`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-r1",
                                    title = "With reminders",
                                    start = "2025-04-15T15:00:00.000Z",
                                    useTime = true,
                                    notifies = listOf(0, 30, 1440),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                            ),
                    ),
            )

        val result = Mapper.map(backup, markCompleted = false)
        val reminders = result.payload.taskReminders

        assertEquals(3, reminders.size, "every legacy notify offset becomes a reminder row")
        assertTrue(reminders.all { it.taskId == "T-r1" }, "reminders stay attached to their task")
        assertEquals(listOf("gentle", "gentle", "gentle"), reminders.map { it.type })

        val base = Mapper.parseInstantMs("2025-04-15T15:00:00.000Z")!!
        val byOffset = reminders.associateBy { it.offsetMinutes }
        assertEquals(base, byOffset.getValue(0).fireAt, "offset 0 fires at start")
        assertEquals(base - 30 * 60_000L, byOffset.getValue(-30).fireAt, "30 minutes before start")
        assertEquals(base - 1440 * 60_000L, byOffset.getValue(-1440).fireAt, "1 day before start")
        assertEquals(
            setOf(0, -30, -1440),
            reminders.map { it.offsetMinutes }.toSet(),
            "legacy 'minutes before' is a negative offset",
        )

        assertEquals(3, result.skipped["reminderSourceRecords"])
        assertEquals(3, result.skipped["remindersImported"])
        assertEquals(0, result.skipped["remindersDuplicatesMerged"])
        assertEquals(0, result.skipped["remindersMissingBaseTime"])
    }

    @Test
    fun `reminders without a faithful base time or read state are reported, not guessed`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-no-start",
                                    title = "No base",
                                    start = null,
                                    notifies = listOf(15),
                                    startNotifiesReaded = listOf(15),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                                LegacyTask(
                                    id = "T-rec",
                                    title = "Template recurrence",
                                    start = null,
                                    recurrence = LegacyRecurrence(notifies = listOf(0)),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                                LegacyTask(
                                    id = "T-ok",
                                    title = "Recoverable",
                                    start = "2025-01-01T10:00:00.000Z",
                                    notifies = listOf(60),
                                    startNotifiesReaded = listOf(60),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                            ),
                    ),
            )

        val result = Mapper.map(backup, markCompleted = false)

        assertEquals(1, result.payload.taskReminders.size, "only the reminder with a computable fire time is preserved")
        assertEquals(
            "T-ok",
            result.payload.taskReminders
                .single()
                .taskId,
        )

        assertEquals(3, result.skipped["reminderSourceRecords"])
        assertEquals(1, result.skipped["remindersImported"])
        assertEquals(0, result.skipped["remindersDuplicatesMerged"])
        assertEquals(2, result.skipped["remindersMissingBaseTime"])
        assertEquals(2, result.skipped["reminderReadStateUnsupported"])
    }

    @Test
    fun `reminder mapping is idempotent so repeated imports do not duplicate rows`() {
        val backup =
            LegacyBackup(
                data =
                    LegacyData(
                        tasks =
                            listOf(
                                LegacyTask(
                                    id = "T-r1",
                                    title = "With reminder",
                                    start = "2025-04-15T15:00:00.000Z",
                                    notifies = listOf(15, 30),
                                    modificatedDate = 1_700_000_000_000L,
                                ),
                            ),
                    ),
            )

        val first = Mapper.map(backup, markCompleted = false).payload.taskReminders
        val second = Mapper.map(backup, markCompleted = false).payload.taskReminders

        assertEquals(
            first.map { it.id }.sorted(),
            second.map { it.id }.sorted(),
            "re-converting yields the same reminder ids",
        )
        assertEquals(
            first,
            second,
            "a repeated conversion produces identical reminder rows for upsert/idempotent import",
        )
        assertEquals(
            listOf("T-r1:legacy-notify:15", "T-r1:legacy-notify:30"),
            first.map { it.id }.sorted(),
        )
    }

    /**
     * Convert-then-read round-trip: the archive the importer writes carries the
     * reminder rows, so restoring it (the native import path) sees non-zero task
     * reminders associated with the correct tasks — the end-to-end property the
     * 2026-11-08 backup lost (1,756 tasks with notifies -> 0 imported).
     */
    @Test
    fun `converted archive contains imported reminder rows`() =
        runTest {
            val backup =
                LegacyBackup(
                    data =
                        LegacyData(
                            tasks =
                                listOf(
                                    LegacyTask(
                                        id = "T-r1",
                                        title = "A",
                                        start = "2025-04-15T15:00:00.000Z",
                                        notifies = listOf(0, 60),
                                        modificatedDate = 1_700_000_000_000L,
                                    ),
                                    LegacyTask(
                                        id = "T-r2",
                                        title = "B",
                                        start = "2025-04-16T08:30:00.000Z",
                                        notifies = listOf(1440),
                                        modificatedDate = 1_700_000_000_000L,
                                    ),
                                ),
                        ),
                )

            val payload = Mapper.map(backup, markCompleted = false).payload
            assertEquals(3, payload.taskReminders.size, "source reminder records must survive conversion")
            assertTrue(payload.taskReminders.all { it.taskId in setOf("T-r1", "T-r2") })

            val tmp = Files.createTempFile("sing-import-reminders", ".zip")
            try {
                val fs = JvmFileSystem()
                val codec = JvmBackupCodec()
                val manifestBytes = """{"formatVersion":1}""".toByteArray()
                val payloadBytes = StableJson.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
                codec.export(manifestBytes, payloadBytes, emptyList(), tmp.toString(), fs).getOrThrow()
                val read = codec.import(tmp.toString(), fs).getOrThrow()
                val restored =
                    StableJson.decodeFromString(
                        BackupPayload.serializer(),
                        read.payloadBytes.decodeToString(),
                    )

                assertEquals(
                    payload.taskReminders.size,
                    restored.taskReminders.size,
                    "archive round-trip must not drop reminder rows",
                )
                assertEquals(
                    payload.taskReminders.map { it.taskId to it.fireAt }.toSet(),
                    restored.taskReminders
                        .map {
                            it.taskId to
                                it.fireAt
                        }.toSet(),
                )
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

    private fun recurrenceOf(
        type: String,
        dayNumber: Int,
        monthNumber: Int? = null,
    ): String {
        val days =
            listOf(
                buildJsonObject {
                    put("dayNumber", dayNumber)
                    monthNumber?.let { put("monthNumber", it) }
                    put("dayType", JsonPrimitive("day"))
                },
            )
        val task =
            LegacyTask(
                id = "T-1",
                title = "A task",
                start = "2026-11-30T21:00:00.000Z",
                recurrence = LegacyRecurrence(repeat = LegacyRepeat(type = type, days = days)),
            )
        return Mapper
            .map(LegacyBackup(data = LegacyData(tasks = listOf(task))), markCompleted = false)
            .payload.tasks
            .single()
            .recurrenceRule
            ?: error("no recurrence rule was mapped for $type")
    }
}
