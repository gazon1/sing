package com.singularity.todo.feature.tasks.presentation.viewmodel

import com.singularity.todo.core.ui.textfields.Revisioned
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The task draft is the screen's copy of the task's text.
 *
 * These tests pin the rule that a rewrite from outside replaces the text, and
 * that the revision — the token the bridged field reads to tell a replacement
 * from an echo — moves exactly when the text was replaced.
 */
@Tag("fast")
class TaskDetailDraftStateTest {

    private fun newState(initial: TaskDetailDraft = TaskDetailDraft.empty()) = TaskDetailDraftState(initial)

    /**
     * A draft that already holds a value.
     *
     * The value and its count travel together, in a [Revisioned], so a draft that is
     * built here says what it holds without saying how many times it was replaced.
     */
    private fun draft(
        title: String,
        description: String,
        originalTitle: String = title,
        originalDescription: String = description,
    ) = TaskDetailDraft(
        title = Revisioned.initial(title),
        description = Revisioned.initial(description),
        originalTitle = originalTitle,
        originalDescription = originalDescription,
        initialized = true,
    )

    // ─── seed ─────────────────────────────────────────────────────────────────

    @Test
    fun `seed sets title and description`() {
        val s = newState()
        s.seed("Buy milk", "Semi-fat")
        assertEquals("Buy milk", s.current.title.value)
        assertEquals("Semi-fat", s.current.description.value)
        assertEquals("Buy milk", s.current.originalTitle)
        assertEquals("Semi-fat", s.current.originalDescription)
        assertTrue(s.current.initialized)
    }

    @Test
    fun `seed on a clean draft takes the rewrite from outside and moves the revision`() {
        val s = newState()
        s.seed("Buy milk", "Semi-fat")
        s.seed("Buy oat milk", "Semi-fat")
        assertEquals("Buy oat milk", s.current.title.value)
        assertEquals(1L, s.current.title.revision)
    }

    @Test
    fun `seed on a dirty draft keeps the user's text and does not move the revision`() {
        val s = newState()
        s.seed("Buy milk", "Semi-fat")
        s.setTitle("Mine")
        s.seed("Buy oat milk", "Semi-fat")
        assertEquals("Mine", s.current.title.value)
        assertEquals(0L, s.current.title.revision)
    }

    @Test
    fun `seed with the text the draft already holds changes nothing`() {
        val s = newState()
        s.seed("Buy milk", "Semi-fat")
        s.seed("Buy milk", "Semi-fat")
        assertEquals(0L, s.current.title.revision)
    }

    @Test
    fun `the revision counts replacements, not edits`() {
        val s = newState()
        s.seed("Buy milk", "Semi-fat")
        s.setTitle("Mine")
        s.seed("Mine", "Semi-fat")
        s.seed("Buy oat milk", "Semi-fat")
        assertEquals(1L, s.current.title.revision)
        s.seed("Buy almond milk", "Semi-fat")
        assertEquals(2L, s.current.title.revision)
    }

    // ─── the echo of the save ─────────────────────────────────────────────────

    @Test
    fun `the echo of the save moves the baseline so the draft is clean`() {
        val s = newState()
        s.seed("Buy milk", "Semi-fat")
        s.setTitle("Mine")
        assertTrue(s.current.isDirty)
        s.seed("Mine", "Semi-fat")
        assertFalse(s.current.isDirty)
        assertEquals(0L, s.current.title.revision)
    }

    @Test
    fun `a later rewrite lands once the save was echoed`() {
        val s = newState()
        s.seed("Buy milk", "Semi-fat")
        s.setTitle("Mine")
        s.seed("Mine", "Semi-fat")
        s.seed("Buy oat milk", "Semi-fat")
        assertEquals("Buy oat milk", s.current.title.value)
        assertEquals(1L, s.current.title.revision)
    }

    @Test
    fun `the echo of a stale write does not clean a newer edit`() {
        val s = newState()
        s.seed("Buy milk", "Semi-fat")
        s.setTitle("Mine")
        s.setTitle("Mine and more")
        s.seed("Mine", "Semi-fat")
        assertTrue(s.current.isDirty)
        s.seed("Buy oat milk", "Semi-fat")
        assertEquals("Mine and more", s.current.title.value)
    }

    // ─── setTitle / setDescription ────────────────────────────────────────────

    @Test
    fun `setTitle updates the title`() {
        val s = newState(draft("init", "desc"))
        s.setTitle("Updated")
        assertEquals("Updated", s.current.title.value)
        assertEquals("desc", s.current.description.value)
    }

    @Test
    fun `setDescription updates the description`() {
        val s = newState(draft("title", "init"))
        s.setDescription("Updated desc")
        assertEquals("title", s.current.title.value)
        assertEquals("Updated desc", s.current.description.value)
    }

    // ─── isDirty ───────────────────────────────────────────────────────────────

    @Test
    fun `isDirty is false when nothing changed`() {
        val s = newState(draft("title", "desc"))
        assertFalse(s.current.isDirty)
    }

    @Test
    fun `isDirty is true when the title changed`() {
        val s = newState(draft("edited", "desc", originalTitle = "original"))
        assertTrue(s.current.isDirty)
    }

    @Test
    fun `isDirty is true when the description changed`() {
        val s = newState(draft("title", "edited", originalDescription = "original"))
        assertTrue(s.current.isDirty)
    }
}
