package com.singularity.todo.feature.tasks.presentation.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskDetailDraftStateTest {

    private fun newState(initial: TaskDetailDraft = TaskDetailDraft.empty()) =
        TaskDetailDraftState(initial)

    // ─── seed ─────────────────────────────────────────────────────────────────

    @Test
    fun `seed sets title and description`() {
        val s = newState()
        s.seed("My Task", "Description")
        assertEquals("My Task", s.current.title)
        assertEquals("Description", s.current.description)
        assertEquals("My Task", s.current.originalTitle)
        assertEquals("Description", s.current.originalDescription)
        assertTrue(s.current.initialized)
    }

    @Test
    fun `seed is idempotent — second call is ignored`() {
        val s = newState()
        s.seed("First", "Desc")
        s.seed("Second", "Other")
        assertEquals("First", s.current.title)
        assertEquals("Desc", s.current.description)
    }

    // ─── setTitle / setDescription ─────────────────────────────────────────────

    @Test
    fun `setTitle updates title`() {
        val s = newState(TaskDetailDraft("init", "desc", "init", "desc", true))
        s.setTitle("Updated")
        assertEquals("Updated", s.current.title)
        assertEquals("desc", s.current.description)
    }

    @Test
    fun `setDescription updates description`() {
        val s = newState(TaskDetailDraft("title", "init", "title", "init", true))
        s.setDescription("Updated desc")
        assertEquals("title", s.current.title)
        assertEquals("Updated desc", s.current.description)
    }

    // ─── isDirty ───────────────────────────────────────────────────────────────

    @Test
    fun `isDirty is false when nothing changed`() {
        val s = newState(TaskDetailDraft("title", "desc", "title", "desc", true))
        assertFalse(s.current.isDirty)
    }

    @Test
    fun `isDirty is true when title changed`() {
        val s = newState(TaskDetailDraft("edited", "desc", "original", "desc", true))
        assertTrue(s.current.isDirty)
    }

    @Test
    fun `isDirty is true when description changed`() {
        val s = newState(TaskDetailDraft("title", "edited", "title", "original", true))
        assertTrue(s.current.isDirty)
    }
}
