package com.singularity.todo.core.links

import com.singularity.todo.feature.notes.domain.logic.extractOutgoingLinks as extractFromEditorHtml
import com.singularity.todo.feature.tasks.data.extractOutgoingLinks as extractFromPlainText
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two surfaces that read internal links must read them the same way.
 *
 * ## Why this test lives here
 *
 * It spans two features on purpose: the point is that they agree. A note body is
 * read from the editor's markup, a task description from plain text, and before the
 * consolidation each had its own copy of the rules — including its own idea of what
 * an identifier may contain. A rule that only one of them follows is a rule nobody
 * can rely on.
 *
 * The vocabulary itself is in `core`; this test lives beside it because that is
 * what it protects.
 */
@Tag("fast")
class InternalLinksAgreementTest {

    private val sampleLinks = listOf("note://n1", "task://t1", "note://n_2-x")

    @Test
    fun `the editor's markup and plain text yield the same links`() {
        val html = sampleLinks.joinToString("") { link -> """<a href="$link">x</a>""" }
        val text = sampleLinks.joinToString(" ")

        assertEquals(
            extractFromPlainText(text).map { it.substringAfter("://") },
            extractFromEditorHtml(html).map { it.id },
        )
    }

    @Test
    fun `both surfaces reject a link whose scheme is not ours`() {
        val html = """<a href="xnote://n1">x</a>"""
        val text = "xnote://n1"

        assertEquals(emptyList(), extractFromEditorHtml(html))
        assertEquals(emptyList(), extractFromPlainText(text))
    }

    @Test
    fun `a URL whose identifier is malformed is not a link`() {
        assertEquals(null, InternalLinks.parse("note://a b"))
        assertEquals(null, InternalLinks.parse("note://a/b"))
        assertEquals(null, InternalLinks.parse("note://a.b"))
    }

    @Test
    fun `free text ends a link at the first character that cannot be part of one`() {
        // "note://a b" in free text is the link "note://a" followed by the word "b".
        // The same characters inside an href are one closed value, and a closed value
        // with a malformed identifier is not a link at all.
        assertEquals(listOf("note://a"), extractFromPlainText("note://a b"))
        assertEquals(emptyList(), extractFromEditorHtml("""<a href="note://a b">x</a>"""))
    }

    @Test
    fun `both surfaces keep the order the links appear in`() {
        val html = """<a href="task://t1">x</a><a href="note://n1">y</a>"""
        val text = "task://t1 note://n1"

        assertEquals(listOf("t1", "n1"), extractFromEditorHtml(html).map { it.id })
        assertEquals(listOf("t1", "n1"), extractFromPlainText(text).map { it.substringAfter("://") })
    }
}
