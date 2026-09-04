package com.singularity.todo.feature.notes

import kotlin.test.Test
import kotlin.test.assertEquals

class NoteFormattersTest {

    @Test
    fun `improved result shows greeting and title`() {
        val r = formatNoteAiResult(NoteAiResult.Improved(title = "New title", body = "<p>body</p>"))
        assertEquals("Note improved!\n\nTitle: New title", r)
    }

    @Test
    fun `error result formats with prefix`() {
        val r = formatNoteAiResult(NoteAiResult.Error(message = "kaboom"))
        assertEquals("Error: kaboom", r)
    }
}
