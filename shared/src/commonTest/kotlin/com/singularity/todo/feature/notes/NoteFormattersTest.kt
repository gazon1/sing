package com.singularity.todo.feature.notes

import kotlin.test.Test
import kotlin.test.assertEquals

class NoteFormattersTest {

    @Test
    fun improvedResultShowsGreetingAndTitle() {
        val r = formatNoteAiResult(NoteAiResult.Improved(title = "New title", body = "<p>body</p>"))
        assertEquals("Note improved!\n\nTitle: New title", r)
    }

    @Test
    fun errorResultFormatsWithPrefix() {
        val r = formatNoteAiResult(NoteAiResult.Error(message = "kaboom"))
        assertEquals("Error: kaboom", r)
    }
}
