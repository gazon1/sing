package com.singularity.todo.feature.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkSchemesTest {

    @Test
    fun `LinkKind urlFor round-trips through parseLinkUrl`() {
        val noteUrl = LinkKind.Note.urlFor("abc123")
        assertEquals("note://abc123", noteUrl)

        val taskUrl = LinkKind.Task.urlFor("xyz789")
        assertEquals("task://xyz789", taskUrl)

        val (notePrefix, noteId) = parseLinkUrl(noteUrl)!!
        assertEquals(LinkSchemes.NOTE_PREFIX, notePrefix)
        assertEquals("abc123", noteId)

        val (taskPrefix, taskId) = parseLinkUrl(taskUrl)!!
        assertEquals(LinkSchemes.TASK_PREFIX, taskPrefix)
        assertEquals("xyz789", taskId)
    }

    @Test
    fun `parseLinkUrl returns null for unknown scheme`() {
        assertNull(parseLinkUrl("https://example.com"))
        assertNull(parseLinkUrl("note://")) // empty id
        assertNull(parseLinkUrl("notelink://abc"))
        assertNull(parseLinkUrl(""))
    }

    @Test
    fun `parseLinkUrl returns null for empty id`() {
        assertNull(parseLinkUrl("note://"))
        assertNull(parseLinkUrl("task://"))
    }
}
