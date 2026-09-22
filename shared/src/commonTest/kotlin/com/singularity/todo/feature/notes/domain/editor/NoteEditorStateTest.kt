package com.singularity.todo.feature.notes.domain.editor

import com.singularity.todo.feature.notes.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteEditorStateTest {

    private val state = NoteEditorState()

    @Test
    fun `initial state is Empty`() {
        assertIs<EditorState.Empty>(state.state.value)
        assertNull(state.current)
    }

    @Test
    fun `open sets Editing state`() {
        state.open(EditorState.Editing("n1", "Title", "<p>Body</p>", isDirty = false))
        val current = state.current
        assertIs<EditorState.Editing>(current)
        assertEquals("n1", current.id)
        assertEquals("Title", current.title)
        assertEquals("<p>Body</p>", current.html)
        assertFalse(current.isDirty)
    }

    @Test
    fun `updateTitle sets dirty`() {
        state.open(EditorState.Editing("n1", "Title", "", isDirty = false))
        state.updateTitle("New Title")
        val current = state.current!!
        assertEquals("New Title", current.title)
        assertTrue(current.isDirty)
    }

    @Test
    fun `updateHtml sets dirty`() {
        state.open(EditorState.Editing("n1", "Title", "", isDirty = false))
        state.updateHtml("<p>New body</p>")
        val current = state.current!!
        assertEquals("<p>New body</p>", current.html)
        assertTrue(current.isDirty)
    }

    @Test
    fun `applyImprove replaces title and html and sets dirty`() {
        state.open(EditorState.Editing("n1", "Old Title", "<p>Old body</p>", isDirty = false))
        state.applyImprove("New Title", "<p>New body</p>")
        val current = state.current!!
        assertEquals("New Title", current.title)
        assertEquals("<p>New body</p>", current.html)
        assertTrue(current.isDirty)
    }

    @Test
    fun `markSaved clears dirty and isNew`() {
        state.open(EditorState.Editing("n1", "Title", "<p>Body</p>", isDirty = true, isNew = true))
        state.markSaved()
        val current = state.current!!
        assertFalse(current.isDirty)
        assertFalse(current.isNew)
    }

    @Test
    fun `markSaved only affects Editing state`() {
        // When state is Empty, markSaved does nothing (no crash, no state change)
        state.clear()
        state.markSaved()
        assertIs<EditorState.Empty>(state.state.value)
    }

    @Test
    fun `clear resets to Empty`() {
        state.open(EditorState.Editing("n1", "Title", "", isDirty = true, isNew = true))
        state.clear()
        assertIs<EditorState.Empty>(state.state.value)
        assertNull(state.current)
    }

    @Test
    fun `updateTitle does nothing when Empty`() {
        state.clear()
        state.updateTitle("title") // must not crash
        assertIs<EditorState.Empty>(state.state.value)
    }

    @Test
    fun `updateHtml does nothing when Empty`() {
        state.clear()
        state.updateHtml("<p>html</p>") // must not crash
        assertIs<EditorState.Empty>(state.state.value)
    }

    @Test
    fun `applyImprove does nothing when Empty`() {
        state.clear()
        state.applyImprove("t", "<p>h</p>") // must not crash
        assertIs<EditorState.Empty>(state.state.value)
    }
}
