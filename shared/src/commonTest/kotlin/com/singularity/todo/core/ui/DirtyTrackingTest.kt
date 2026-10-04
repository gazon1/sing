package com.singularity.todo.core.ui

import com.singularity.todo.feature.notes.EditorState
import com.singularity.todo.feature.notes.domain.editor.NoteEditorState
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests dirty-tracking (hasUnsavedChanges) across editor types.
 *
 * [DraftState] is the canonical dirty-tracking primitive: it holds a checkpoint
 * (last known-saved value) and compares the current value against it.
 *
 * [NoteEditorState] is the primary consumer — its `Editing.isDirty` field is set
 * by user actions and cleared on save.
 *
 * A bug in either layer causes the "You have unsaved changes" prompt to fire
 * incorrectly (false positive) or not fire when it should (false negative).
 */
@Tag("fast")
class DirtyTrackingTest {

    // ─── DraftState — pure dirty tracking ─────────────────────────────────────

    @Test
    fun `DraftState isClean after init`() {
        val draft = DraftState("initial")
        assertFalse(draft.isDirty)
        assertEquals("initial", draft.value)
    }

    @Test
    fun `DraftState isDirty after edit`() {
        val draft = DraftState("initial")
        draft.edit { "changed" }
        assertTrue(draft.isDirty)
        assertEquals("changed", draft.value)
    }

    @Test
    fun `DraftState isClean after checkpoint`() {
        val draft = DraftState("initial")
        draft.edit { "changed" }
        assertTrue(draft.isDirty)
        draft.checkpoint()
        assertFalse(draft.isDirty)
        assertEquals("changed", draft.value)
    }

    @Test
    fun `DraftState discard reverts to checkpoint`() {
        val draft = DraftState("initial")
        draft.edit { "changed" }
        draft.discard()
        assertFalse(draft.isDirty)
        assertEquals("initial", draft.value)
    }

    @Test
    fun `DraftState discard does not move checkpoint`() {
        val draft = DraftState("initial")
        draft.edit { "v1" }
        draft.checkpoint() // checkpoint = "v1"
        draft.edit { "v2" }
        assertTrue(draft.isDirty)
        draft.discard() // should revert to "v1" (the checkpoint), not "initial"
        assertEquals("v1", draft.value)
        assertFalse(draft.isDirty)
    }

    @Test
    fun `DraftState open resets checkpoint and current to new value`() {
        val draft = DraftState("initial")
        draft.edit { "changed" }
        assertTrue(draft.isDirty)
        draft.open("new-entity")
        assertFalse(draft.isDirty)
        assertEquals("new-entity", draft.value)
    }

    @Test
    fun `DraftState edit does not affect checkpoint`() {
        val draft = DraftState("initial")
        draft.edit { "changed" }
        assertTrue(draft.isDirty)
        draft.checkpoint()
        draft.edit { "changed-again" }
        assertTrue(draft.isDirty)
        draft.discard()
        assertEquals("changed", draft.value) // back to checkpoint, not "initial"
    }

    @Test
    fun `DraftState with value class type tracks dirty correctly`() {
        // Simulates a TaskEditor-style data class draft
        data class TaskDraft(val title: String, val body: String = "")

        val draft = DraftState(TaskDraft("Buy milk"))
        assertFalse(draft.isDirty)

        draft.edit { it.copy(title = "Buy coffee") }
        assertTrue(draft.isDirty)

        draft.checkpoint()
        assertFalse(draft.isDirty)
        assertEquals("Buy coffee", draft.value.title)
    }

    // ─── NoteEditorState — Editing.isDirty ──────────────────────────────────

    @Test
    fun `NoteEditorState initial state is Empty`() {
        val state = NoteEditorState()
        assertTrue(state.state.value is EditorState.Empty)
    }

    @Test
    fun `NoteEditorState updateTitle sets isDirty`() {
        val state = NoteEditorState()
        state.open(EditorState.Editing("n1", "Title", "", isDirty = false))
        state.updateTitle("New Title")
        val current = state.current!!
        assertTrue(current.isDirty)
        assertEquals("New Title", current.title)
    }

    @Test
    fun `NoteEditorState updateHtml sets isDirty`() {
        val state = NoteEditorState()
        state.open(EditorState.Editing("n1", "Title", "", isDirty = false))
        state.updateHtml("<p>New body</p>")
        val current = state.current!!
        assertTrue(current.isDirty)
        assertEquals("<p>New body</p>", current.html)
    }

    @Test
    fun `NoteEditorState markSaved clears isDirty`() {
        val state = NoteEditorState()
        state.open(EditorState.Editing("n1", "Title", "<p>Body</p>", isDirty = true))
        state.markSaved()
        val current = state.current!!
        assertFalse(current.isDirty)
    }

    @Test
    fun `NoteEditorState clear resets to Empty`() {
        val state = NoteEditorState()
        state.open(EditorState.Editing("n1", "Title", "", isDirty = true))
        state.clear()
        assertTrue(state.state.value is EditorState.Empty)
    }

    @Test
    fun `NoteEditorState applyImprove sets isDirty`() {
        val state = NoteEditorState()
        state.open(EditorState.Editing("n1", "Old", "<p>old</p>", isDirty = false))
        state.applyImprove("New Title", "<p>New body</p>")
        val current = state.current!!
        assertTrue(current.isDirty)
        assertEquals("New Title", current.title)
        assertEquals("<p>New body</p>", current.html)
    }

    @Test
    fun `NoteEditorState operations on Empty do not crash`() {
        val state = NoteEditorState()
        state.updateTitle("t") // must not throw
        state.updateHtml("<p>h</p>") // must not throw
        state.applyImprove("t", "<p>h</p>") // must not throw
        state.markSaved() // must not throw
        assertTrue(state.state.value is EditorState.Empty)
    }

    // ─── DraftState and NoteEditorState are independent ─────────────────────

    @Test
    fun `DraftState and NoteEditorState isDirty are independent mechanisms`() {
        // DraftState tracks at the entity level (whole draft vs checkpoint)
        val draft = DraftState("initial")
        draft.edit { "changed" }
        assertTrue(draft.isDirty)

        // NoteEditorState tracks at the field level (per-field isDirty flag)
        val editor = NoteEditorState()
        editor.open(EditorState.Editing("n1", "Title", "", isDirty = false))
        editor.updateTitle("Changed")
        assertTrue(editor.current!!.isDirty)

        // They are different mechanisms; clearing one doesn't affect the other
        editor.markSaved()
        assertFalse(editor.current!!.isDirty)
        assertTrue(draft.isDirty) // DraftState is unaffected
    }

    // ─── hasUnsavedChanges as derived concept ────────────────────────────────

    @Test
    fun `DraftState can represent hasUnsavedChanges semantics`() {
        // Any non-checkpoint value means "unsaved changes exist"
        val draft = DraftState("saved-title")
        assertFalse(draft.isDirty) // hasUnsavedChanges = false

        draft.edit { "saved-title" }
        assertFalse(draft.isDirty) // identical value → clean

        draft.edit { "unsaved-title" }
        assertTrue(draft.isDirty) // hasUnsavedChanges = true
    }
}
