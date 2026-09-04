package com.singularity.todo.feature.tasks

import com.singularity.todo.feature.settings.ReminderOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskEditorReducerTest {

    private val initial = TaskEditorUiState()

    @Test fun `TitleChanged sets title and clears error`() {
        val r = initial.reduce(TaskEditorIntent.TitleChanged("Buy milk"))
        assertEquals("Buy milk", r.title)
        assertNull(r.errorMessage)
    }

    @Test fun `DescriptionChanged sets description`() {
        val r = initial.reduce(TaskEditorIntent.DescriptionChanged("from store"))
        assertEquals("from store", r.description)
    }

    @Test fun `DueDateChanged sets dueDate`() {
        val r = initial.reduce(TaskEditorIntent.DueDateChanged(kotlinx.datetime.LocalDate(2026, 1, 1)))
        assertEquals(kotlinx.datetime.LocalDate(2026, 1, 1), r.dueDate)
    }

    @Test fun `DueTimeChanged sets dueTime`() {
        val t = kotlinx.datetime.LocalTime(9, 30)
        val r = initial.reduce(TaskEditorIntent.DueTimeChanged(t))
        assertEquals(t, r.dueTime)
    }

    @Test fun `ProjectChanged sets projectId`() {
        val r = initial.reduce(TaskEditorIntent.ProjectChanged("p1"))
        assertEquals("p1", r.projectId)
    }

    @Test fun `TagsChanged sets tagIds`() {
        val r = initial.reduce(TaskEditorIntent.TagsChanged(listOf("t1", "t2")))
        assertEquals(listOf("t1", "t2"), r.tagIds)
    }

    @Test fun `NewChecklistItemChanged sets draft`() {
        val r = initial.reduce(TaskEditorIntent.NewChecklistItemChanged("draft"))
        assertEquals("draft", r.newChecklistItem)
    }

    @Test fun `ReminderOffsetChanged sets offset`() {
        val r = initial.reduce(TaskEditorIntent.ReminderOffsetChanged(ReminderOffset.FIFTEEN_MIN))
        assertEquals(ReminderOffset.FIFTEEN_MIN, r.reminderOffset)
    }

    @Test fun `AddAttachment appends to pendingAttachments`() {
        val r = initial.reduce(TaskEditorIntent.AddAttachment("/tmp/a", "a.txt", "text/plain"))
        assertEquals(1, r.pendingAttachments.size)
        assertEquals("/tmp/a", r.pendingAttachments[0].path)
    }

    @Test fun `ErrorShown clears errorMessage`() {
        val withError = initial.copy(errorMessage = "boom")
        val r = withError.reduce(TaskEditorIntent.ErrorShown)
        assertNull(r.errorMessage)
    }

    @Test fun `impure intents are pass-through`() {
        val r = initial.reduce(TaskEditorIntent.AddChecklistItem)
        assertEquals(initial, r)
        val r2 = initial.reduce(TaskEditorIntent.ToggleChecklistItem("x"))
        assertEquals(initial, r2)
        val r3 = initial.reduce(TaskEditorIntent.DeleteChecklistItem("x"))
        assertEquals(initial, r3)
        val r4 = initial.reduce(TaskEditorIntent.Save)
        assertEquals(initial, r4)
        assertTrue(initial.checklistItems.isEmpty())
    }
}
