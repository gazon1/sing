package com.singularity.todo.core.attachments.annotation

import com.singularity.todo.core.attachments.AttachmentId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The anchor ordering, and the validation that makes a malformed range unconstructible.
 *
 * `resolveAnchor` is a pure function precisely so this file can exist without a database,
 * a device or a Compose runtime — the ordering is the decision ADR
 * `2026-10-07-annotation-anchor-model` records, and a decision that cannot be executed
 * without a phone is a decision nothing checks.
 *
 * `fast`: no process boundary is crossed. It is arithmetic over strings.
 */
@Tag("fast")
class AnnotationAnchorResolutionTest {

    private val attachment = AttachmentId.fromString("att_test")

    private fun range(start: Int, end: Int, quote: String) =
        TextRange(attachmentId = attachment, start = start, end = end, quote = quote)

    // ─── Construction ───────────────────────────────────────────────────────────

    @Test
    fun `a range cannot be constructed backwards`() {
        val failure = assertFailsWith<IllegalArgumentException> { range(start = 10, end = 4, quote = "x") }

        assertTrue(
            failure.message!!.contains("start"),
            "the message should name the offending field: ${failure.message}",
        )
    }

    @Test
    fun `an empty range cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { range(start = 5, end = 5, quote = "x") }
    }

    @Test
    fun `a negative start cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { range(start = -1, end = 4, quote = "x") }
    }

    @Test
    fun `a quote over the character limit cannot be constructed`() {
        val tooLong = "a".repeat(MAX_ANNOTATION_QUOTE_CHARS + 1)
        assertFailsWith<IllegalArgumentException> { range(start = 0, end = tooLong.length, quote = tooLong) }
    }

    @Test
    fun `a quote exactly at the limit is allowed`() {
        val atLimit = "a".repeat(MAX_ANNOTATION_QUOTE_CHARS)
        val built = range(start = 0, end = atLimit.length, quote = atLimit)
        assertEquals(MAX_ANNOTATION_QUOTE_CHARS, built.quote.length)
    }

    @Test
    fun `a range without an attachment cannot be constructed`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            TextRange(attachmentId = AttachmentId.fromString(""), start = 0, end = 1, quote = "x")
        }
        assertTrue(
            failure.message!!.contains("attachmentId"),
            "the message should name the missing attachment: ${failure.message}",
        )
    }

    // ─── Exact ──────────────────────────────────────────────────────────────────

    @Test
    fun `offsets that still hold the quote resolve to Exact`() {
        val text = "alpha beta gamma"
        val resolution = resolveAnchor(range(start = 6, end = 10, quote = "beta"), text)

        assertEquals(AnchorResolution.Exact(6, 10), resolution)
    }

    // ─── QuoteFound ─────────────────────────────────────────────────────────────

    @Test
    fun `a note whose offsets moved resolves to the quote's new position`() {
        // "beta" is at 6; two characters are inserted above it, so the offsets now address
        // "ha beta" — and anchoring there without the quote fallback is the defect.
        val text = "XXalpha beta gamma"
        val resolution = resolveAnchor(range(start = 6, end = 10, quote = "beta"), text)

        assertEquals(AnchorResolution.QuoteFound(8, 12), resolution)
    }

    @Test
    fun `a repeated quote resolves to its first occurrence`() {
        // Documented behaviour rather than an accident: `indexOf` is the first match, and
        // there is no tie-breaker that would prefer the stored offsets — those are only
        // consulted first when they are exact.
        val text = "go go"
        val resolution = resolveAnchor(range(start = 3, end = 5, quote = "go"), text)

        assertEquals(AnchorResolution.QuoteFound(0, 2), resolution)
    }

    // ─── Stale ──────────────────────────────────────────────────────────────────

    @Test
    fun `a quote that has left the file resolves to Stale`() {
        val text = "a completely rewritten document"
        val resolution = resolveAnchor(range(start = 6, end = 10, quote = "beta"), text)

        assertEquals(AnchorResolution.Stale, resolution)
    }

    @Test
    fun `offsets past the end of a shorter file resolve to Stale`() {
        val text = "short"
        val resolution = resolveAnchor(range(start = 6, end = 10, quote = "beta"), text)

        assertEquals(AnchorResolution.Stale, resolution)
    }

    @Test
    fun `an empty quote resolves to Stale rather than matching at offset zero`() {
        // The falseness this prevents: an empty quote matches at 0 in every document, so
        // every such annotation would report itself as exactly anchored.
        val resolution = resolveAnchor(range(start = 0, end = 1, quote = ""), "anything at all")

        assertEquals(AnchorResolution.Stale, resolution)
    }

    @Test
    fun `matching is case sensitive`() {
        val resolution = resolveAnchor(range(start = 0, end = 4, quote = "Beta"), "beta")

        assertEquals(AnchorResolution.Stale, resolution)
    }

    @Test
    fun `all three outcomes are reachable`() {
        // A gate that cannot show its own three cases is not pinning the ordering.
        //
        // The second fixture used offsets 8..12 on "XXalpha beta gamma", which do hold the
        // quote *and* sit where its first occurrence is — so it was a second `Exact`, and
        // this test could not demonstrate the `quote` outcome it exists to demonstrate. The
        // offsets that actually moved are 6..10, the same pair the dedicated
        // `a note whose offsets moved` test uses.
        val text = "alpha beta gamma"
        val outcomes = listOf(
            resolveAnchor(range(start = 6, end = 10, quote = "beta"), text),
            resolveAnchor(range(start = 6, end = 10, quote = "beta"), "XXalpha beta gamma"),
            resolveAnchor(range(start = 6, end = 10, quote = "beta"), "unrelated"),
        )

        // Each outcome is one of the three, and all three are distinct values — the check
        // that the ordering is genuinely three-way and not two branches with a name change.
        val classified = outcomes.map {
            when (it) {
                is AnchorResolution.Exact -> "exact"
                is AnchorResolution.QuoteFound -> "quote"
                AnchorResolution.Stale -> "stale"
            }
        }

        assertEquals(listOf("exact", "quote", "stale"), classified)
        assertEquals(3, outcomes.distinct().size, "the three outcomes must be three distinct values")
    }

    // ─── Derived staleness on the annotation ────────────────────────────────────

    @Test
    fun `an annotation reports itself stale only against the text it no longer appears in`() {
        val annotation = AttachmentAnnotation(
            id = AttachmentAnnotationId.generate(),
            range = range(start = 6, end = 10, quote = "beta"),
            note = "the second word",
            userId = com.singularity.todo.core.ids.UserId.anonymous,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
            updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        )

        assertFalse(annotation.isStaleIn("alpha beta gamma"), "the note still matches the file")
        assertTrue(annotation.isStaleIn("nothing like it here"), "the quote is gone from the file")
    }
}
