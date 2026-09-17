package com.singularity.todo.feature.projects.presentation.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectDetailDraftStateTest {

    private fun newState(initial: ProjectDetailDraft = ProjectDetailDraft.empty()) =
        ProjectDetailDraftState(initial)

    // ─── seed ─────────────────────────────────────────────────────────────────

    @Test
    fun `seed sets name and description`() {
        val s = newState()
        s.seed("My Project", "A short description")
        assertEquals("My Project", s.current.name)
        assertEquals("A short description", s.current.description)
        assertEquals("My Project", s.current.originalName)
        assertEquals("A short description", s.current.originalDescription)
        assertTrue(s.current.initialized)
    }

    @Test
    fun `seed is idempotent — second call is ignored`() {
        val s = newState()
        s.seed("First", "Desc")
        s.seed("Second", "Other")
        assertEquals("First", s.current.name)
        assertEquals("Desc", s.current.description)
    }

    // ─── setName / setDescription ─────────────────────────────────────────────

    @Test
    fun `setName updates name`() {
        val s = newState(ProjectDetailDraft("init", "desc", "init", "desc", true))
        s.setName("Updated")
        assertEquals("Updated", s.current.name)
        assertEquals("desc", s.current.description)
    }

    @Test
    fun `setDescription updates description`() {
        val s = newState(ProjectDetailDraft("title", "init", "title", "init", true))
        s.setDescription("Updated desc")
        assertEquals("title", s.current.name)
        assertEquals("Updated desc", s.current.description)
    }

    // ─── isDirty ───────────────────────────────────────────────────────────────

    @Test
    fun `isDirty is false when nothing changed`() {
        val s = newState(ProjectDetailDraft("title", "desc", "title", "desc", true))
        assertFalse(s.current.isDirty)
    }

    @Test
    fun `isDirty is true when name changed`() {
        val s = newState(ProjectDetailDraft("edited", "desc", "original", "desc", true))
        assertTrue(s.current.isDirty)
    }

    @Test
    fun `isDirty is true when description changed`() {
        val s = newState(ProjectDetailDraft("title", "edited", "title", "original", true))
        assertTrue(s.current.isDirty)
    }
}
