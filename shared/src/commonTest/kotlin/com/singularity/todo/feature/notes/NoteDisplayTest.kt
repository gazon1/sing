package com.singularity.todo.feature.notes

import com.singularity.todo.core.ids.UserId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@Tag("fast")
class NoteDisplayTest {
    private val userId = UserId("u")
    private val now = Instant.fromEpochMilliseconds(0)

    private fun note(
        title: String = "",
        bodyHtml: String? = null,
        bodyMarkdown: String? = null,
        isFolder: Boolean = false,
    ) = Note(
        id = NoteId.fromString("n1"),
        userId = userId,
        title = title,
        bodyHtml = bodyHtml,
        bodyMarkdown = bodyMarkdown,
        isFolder = isFolder,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun explicitTitleWinsAndSnippetShowsBody() {
        val n = note(title = "My note", bodyHtml = "<h1>Heading</h1><p>Alpha</p>")
        assertEquals("My note", n.displayTitle())
        assertEquals("Heading Alpha", n.displaySnippet())
    }

    @Test
    fun headingFirstDerivesTitleAndSnippetOmitsHeading() {
        val n = note(bodyHtml = "<h2>Trip plan</h2><p>Pack passport</p>")
        assertEquals("Trip plan", n.displayTitle())
        assertEquals("Pack passport", n.displaySnippet())
    }

    @Test
    fun paragraphFirstDerivesTitle() {
        val n = note(bodyHtml = "<p>Pack passport</p>")
        assertEquals("Pack passport", n.displayTitle())
        assertEquals("", n.displaySnippet())
    }

    @Test
    fun listFirstDerivesFirstItemText() {
        val n = note(bodyHtml = "<ul><li>Buy milk</li><li>Bread</li></ul>")
        assertEquals("Buy milk", n.displayTitle())
    }

    @Test
    fun emptyAndSentinelBodiesUseUntitled() {
        assertEquals("Untitled", note(bodyHtml = null, bodyMarkdown = null).displayTitle())
        assertEquals("Untitled", note(bodyHtml = "").displayTitle())
        assertEquals("Untitled", note(bodyHtml = "<p><br></p>").displayTitle())
        assertEquals("", note(bodyHtml = "<p><br></p>").displaySnippet())
    }

    @Test
    fun formattingOnlyBodiesUseUntitled() {
        assertEquals("Untitled", note(bodyHtml = "<p><strong> </strong></p>").displayTitle())
        assertEquals("Untitled", note(bodyHtml = "<p><em></em></p>").displayTitle())
        assertEquals("", note(bodyHtml = "<p><strong> </strong></p>").displaySnippet())
    }

    @Test
    fun entitiesAreDecodedAndMarkupNotExposed() {
        val n = note(bodyHtml = "<p>Tom &amp; Jerry &lt;3</p>")
        assertEquals("Tom & Jerry <3", n.displayTitle())
        val snippet = note(title = "X", bodyHtml = "<p>Tom &amp; Jerry &lt;3</p>").displaySnippet()
        assertEquals("Tom & Jerry <3", snippet)
        assertFalse(snippet.contains("&amp;"))
        assertFalse(snippet.contains("<p>"))

        val numeric = note(bodyHtml = "<p>Wait&mdash;go &#8212; now</p>")
        assertEquals("Wait\u2014go \u2014 now", numeric.displayTitle())
        val numericSnippet = note(title = "X", bodyHtml = "<p>Wait&mdash;go &#8212; now</p>").displaySnippet()
        assertEquals("Wait\u2014go \u2014 now", numericSnippet)
        assertFalse(numericSnippet.contains("&mdash;"))
        assertFalse(numericSnippet.contains("&#8212;"))
    }

    @Test
    fun markdownOnlyBodyIsReadableWithoutRawSyntax() {
        val n = note(bodyMarkdown = "# Heading\n\n**Bold** text")
        assertEquals("Heading", n.displayTitle())
        val snippet = note(title = "Explicit", bodyMarkdown = "# Heading\n\n**Bold** text").displaySnippet()
        assertFalse(snippet.contains("**"))
        assertFalse(snippet.contains("# "))
    }

    @Test
    fun namedEntitiesOutsideTheCommonSetAreDecoded() {
        val n = note(bodyHtml = "<p>Caf&eacute; cr&egrave;me</p>")
        assertEquals("Caf\u00E9 cr\u00E8me", n.displayTitle())
        assertFalse(n.displayTitle().contains("&eacute;"))
    }

    @Test
    fun quotedAttributeWithGreaterThanDoesNotLeakMarkup() {
        val n = note(bodyHtml = """<p>See <a title="A > B" href="x">Link</a> now</p>""")
        assertEquals("See Link now", n.displayTitle())
        assertFalse(n.displayTitle().contains("href"))
    }

    @Test
    fun scriptAndStyleContentIsNotShown() {
        val n = note(bodyHtml = "<style>p{color:red}</style><script>alert('x')</script><p>Hello</p>")
        assertEquals("Hello", n.displayTitle())
        assertFalse(n.displayTitle().contains("color"))
        assertFalse(n.displayTitle().contains("alert"))
    }

    @Test
    fun foldersDoNotDeriveTitlesFromChildren() {
        val folder = note(isFolder = true, bodyHtml = "<p>Child name</p>")
        assertEquals("Untitled", folder.displayTitle())
        assertEquals("Child name", folder.displaySnippet())
    }

    @Test
    fun longTitleAndSnippetTruncate() {
        val long = "a".repeat(200)
        val derived = note(bodyHtml = "<p>$long</p>")
        val title = derived.displayTitle()
        assertTrue(title.length <= 81)
        assertTrue(title.endsWith("…"))

        val explicit = note(title = "T", bodyHtml = "<p>$long</p>")
        val snippet = explicit.displaySnippet()
        assertTrue(snippet.length <= 121)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun longDerivedFirstBlockIsNotRepeatedInSnippet() {
        val long = "a".repeat(200)
        val derived = note(bodyHtml = "<p>$long</p><p>Second</p>")
        val title = derived.displayTitle()
        assertTrue(title.endsWith("…"))
        assertEquals("Second", derived.displaySnippet())
    }

    @Test
    fun longSingleBlockSnippetShowsWhatTheTitleCouldNot() {
        val long = "a".repeat(200)
        val derived = note(bodyHtml = "<p>$long</p>")
        assertEquals("a".repeat(80) + "…", derived.displayTitle())
        assertEquals("a".repeat(120), derived.displaySnippet())
    }
}
