package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.text.Revisioned
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("fast")
class ProjectDetailDraftStateTest {

    private fun newState(initial: ProjectDetailDraft = ProjectDetailDraft.empty()) = ProjectDetailDraftState(initial)

    /**
     * A draft that already holds a value.
     *
     * The value and its count travel together, in a [Revisioned], so a draft that is
     * built here says what it holds without saying how many times it was replaced.
     */
    private fun draft(
        name: String,
        description: String,
        originalName: String = name,
        originalDescription: String = description,
    ) = ProjectDetailDraft(
        name = Revisioned.initial(name),
        description = Revisioned.initial(description),
        originalName = originalName,
        originalDescription = originalDescription,
        initialized = true,
    )

    // ─── seed ─────────────────────────────────────────────────────────────────

    @Test
    fun `seed sets name and description`() {
        val s = newState()
        s.seed("My Project", "A short description")
        assertEquals("My Project", s.current.name.value)
        assertEquals("A short description", s.current.description.value)
        assertEquals("My Project", s.current.originalName)
        assertEquals("A short description", s.current.originalDescription)
        assertTrue(s.current.initialized)
    }

    @Test
    fun `seed on a clean draft takes the rewrite from outside and moves the revision`() {
        val s = newState()
        s.seed("First", "Desc")
        s.seed("Second", "Other")
        assertEquals("Second", s.current.name.value)
        assertEquals("Other", s.current.description.value)
        assertEquals(1L, s.current.name.revision)
    }

    @Test
    fun `seed on a dirty draft keeps the user's text and does not move the revision`() {
        val s = newState()
        s.seed("First", "Desc")
        s.setName("Mine")
        s.seed("Second", "Other")
        assertEquals("Mine", s.current.name.value)
        assertEquals("Desc", s.current.description.value)
        assertEquals(0L, s.current.name.revision)
    }

    @Test
    fun `seed with the text the draft already holds changes nothing`() {
        val s = newState()
        s.seed("First", "Desc")
        s.seed("First", "Desc")
        assertEquals(0L, s.current.name.revision)
    }

    @Test
    fun `the revision counts replacements, not edits`() {
        val s = newState()
        s.seed("First", "Desc")
        s.setName("Mine")
        s.seed("Mine", "Desc")
        s.seed("Second", "Desc")
        assertEquals(1L, s.current.name.revision)
        s.seed("Third", "Desc")
        assertEquals(2L, s.current.name.revision)
    }

    // ─── the echo of the save ─────────────────────────────────────────────────

    @Test
    fun `the echo of the save moves the baseline so the draft is clean`() {
        val s = newState()
        s.seed("First", "Desc")
        s.setName("Mine")
        assertTrue(s.current.isDirty)
        s.seed("Mine", "Desc")
        assertFalse(s.current.isDirty)
        assertEquals(0L, s.current.name.revision)
    }

    @Test
    fun `a later rewrite lands once the save was echoed`() {
        val s = newState()
        s.seed("First", "Desc")
        s.setName("Mine")
        s.seed("Mine", "Desc")
        s.seed("Second", "Desc")
        assertEquals("Second", s.current.name.value)
        assertEquals(1L, s.current.name.revision)
    }

    @Test
    fun `the echo of a stale write does not clean a newer edit`() {
        val s = newState()
        s.seed("First", "Desc")
        s.setName("Mine")
        s.setName("Mine and more")
        s.seed("Mine", "Desc")
        assertTrue(s.current.isDirty)
        s.seed("Second", "Desc")
        assertEquals("Mine and more", s.current.name.value)
    }

    // ─── setName / setDescription ─────────────────────────────────────────────

    @Test
    fun `setName updates name`() {
        val s = newState(draft("init", "desc"))
        s.setName("Updated")
        assertEquals("Updated", s.current.name.value)
        assertEquals("desc", s.current.description.value)
    }

    @Test
    fun `setDescription updates description`() {
        val s = newState(draft("title", "init"))
        s.setDescription("Updated desc")
        assertEquals("title", s.current.name.value)
        assertEquals("Updated desc", s.current.description.value)
    }

    // ─── isDirty ───────────────────────────────────────────────────────────────

    @Test
    fun `isDirty is false when nothing changed`() {
        val s = newState(draft("title", "desc"))
        assertFalse(s.current.isDirty)
    }

    @Test
    fun `isDirty is true when name changed`() {
        val s = newState(draft("edited", "desc", originalName = "original"))
        assertTrue(s.current.isDirty)
    }

    @Test
    fun `isDirty is true when description changed`() {
        val s = newState(draft("title", "edited", originalDescription = "original"))
        assertTrue(s.current.isDirty)
    }
}
