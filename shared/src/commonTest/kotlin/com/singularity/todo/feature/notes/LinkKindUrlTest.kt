package com.singularity.todo.feature.notes

import com.singularity.todo.core.links.InternalLinkSchemes
import com.singularity.todo.core.links.InternalLinks
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The notes' view of the app's link vocabulary.
 *
 * The vocabulary itself lives in `core`. This test pins the one thing the notes
 * add to it: the link the picker builds has to be a link the app can read back.
 */
@Tag("fast")
class LinkKindUrlTest {

    @Test
    fun `a note link round-trips through the reader`() {
        val url = LinkKind.Note.urlFor("abc123")
        assertEquals("note://abc123", url)

        val target = InternalLinks.parse(url)
        assertEquals(InternalLinkSchemes.NOTE, target?.scheme)
        assertEquals("abc123", target?.id)
    }

    @Test
    fun `a task link round-trips through the reader`() {
        val url = LinkKind.Task.urlFor("xyz789")
        assertEquals("task://xyz789", url)

        val target = InternalLinks.parse(url)
        assertEquals(InternalLinkSchemes.TASK, target?.scheme)
        assertEquals("xyz789", target?.id)
    }

    @Test
    fun `the reader rejects a scheme the app does not own`() {
        assertNull(InternalLinks.parse("https://example.com"))
        assertNull(InternalLinks.parse("notelink://abc"))
        assertNull(InternalLinks.parse(""))
    }

    @Test
    fun `the reader rejects an empty identifier`() {
        assertNull(InternalLinks.parse("note://"))
        assertNull(InternalLinks.parse("task://"))
    }
}
