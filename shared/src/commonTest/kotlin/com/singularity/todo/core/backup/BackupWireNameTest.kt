package com.singularity.todo.core.backup

import com.singularity.todo.core.database.NoteEntity
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.feature.notes.NoteKind
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The backup format names its values by contract, not by the name of the Kotlin
 * constant.
 *
 * Two directions, and both are load-bearing. A payload written before the format
 * named its values — `High`, `NextUp` — must still restore, or an archive taken in
 * March is lost in April. And a payload this build writes must say what this build
 * means by it, or the format is a hostage to the next rename.
 *
 * See `docs/decisions/2026-11-06-the-stored-spellings-are-contracts.md`.
 */
@Tag("fast")
class BackupWireNameTest {
    @Test
    fun `a payload written before the format named its values still restores`() {
        val underTheOldName = task(priority = "High", kind = "Task", workflowState = "NextUp")
        val underTheNewName = task(priority = "high", kind = "task", workflowState = "next_up")

        listOf(underTheOldName, underTheNewName).forEach { dto ->
            val entity = dto.toEntity("u1")
            assertEquals(TaskPriority.High, entity.priority, "the priority says what it says, however it is spelled")
            assertEquals(TaskKind.Task, entity.kind, "the kind says what it says, however it is spelled")
            assertEquals("NextUp", entity.workflowState, "the column keeps the database's own spelling")
        }
    }

    @Test
    fun `a payload this build writes carries the names the format declares`() {
        val dto =
            entity(
                priority = TaskPriority.High,
                kind = TaskKind.Note,
                workflowState = "NextUp",
            ).toDto()

        assertEquals("high", dto.priority, "the format names a priority `high`, not `High`")
        assertEquals("note", dto.kind, "the format names a kind `note`, not `Note`")
        assertEquals("next_up", dto.workflowState, "the format names a workflow state `next_up`, not `NextUp`")
    }

    @Test
    fun `a note carries the names the format declares`() {
        val underTheOldName = NoteDto(id = "n1", title = "A note", kind = "Daily", createdAt = 1, updatedAt = 1)
        val underTheNewName = NoteDto(id = "n1", title = "A note", kind = "daily", createdAt = 1, updatedAt = 1)

        assertEquals(NoteKind.Daily, underTheOldName.toEntity("u1").kind)
        assertEquals(NoteKind.Daily, underTheNewName.toEntity("u1").kind)
        assertEquals("daily", noteEntity().toDto().kind, "the format names a note kind `daily`, not `Daily`")
    }

    /** A payload row that spells its values the way the format does not. */
    private fun task(
        priority: String,
        kind: String,
        workflowState: String,
    ) = TaskDto(
        id = "t1",
        title = "A task",
        priority = priority,
        kind = kind,
        workflowState = workflowState,
        createdAt = 1,
        updatedAt = 1,
    )

    /** A stored task that spells its values the way the database does. */
    private fun entity(
        priority: TaskPriority = TaskPriority.None,
        kind: TaskKind = TaskKind.Task,
        workflowState: String = "Open",
    ) = TaskEntity(
        id = "t1",
        title = "A task",
        description = null,
        priority = priority,
        kind = kind,
        projectId = null,
        dueDate = null,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        completedAt = null,
        archivedAt = null,
        createdAt = 1,
        updatedAt = 1,
        userId = "u1",
        workflowState = workflowState,
    )

    /** A stored note that spells its kind the way the database does. */
    private fun noteEntity() =
        NoteEntity(
            id = "n1",
            userId = "u1",
            title = "A note",
            bodyMarkdown = null,
            bodyHtml = null,
            parentNoteId = null,
            kind = NoteKind.Daily,
            createdAt = 1,
            updatedAt = 1,
            deletedAt = null,
            archivedAt = null,
        )
}
