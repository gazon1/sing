package com.singularity.todo.importer

import com.singularity.todo.core.backup.BackupFormat
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
