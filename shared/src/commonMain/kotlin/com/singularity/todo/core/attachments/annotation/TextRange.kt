package com.singularity.todo.core.attachments.annotation

import com.singularity.todo.core.attachments.AttachmentId

/**
 * The ceiling on [TextRange.quote].
 *
 * A quote is a *copy* of text the file already contains, so an unbounded quote is the one
 * way an annotation can store a whole document and make every read of the table carry it.
 * 1000 characters is well past the longest sentence a note is written against, and past it
 * the quote stops being evidence of what the user selected and becomes a copy of the file.
 */
const val MAX_ANNOTATION_QUOTE_CHARS: Int = 1000

/**
 * A span of one text attachment, carrying both the coordinates and the words.
 *
 * ## Why both
 *
 * Offsets alone are a coordinate in a document with no version attached. Insert a
 * paragraph at the top and every annotation silently addresses the wrong words, with
 * nothing in the data able to notice. The quote is what survives the edit: it is the part
 * the user actually recognised. See [resolveAnchor] for how the two are used together, and
 * ADR `2026-10-07-annotation-anchor-model` for why the fallback is not a guess.
 *
 * ## Why validation is in the constructor and not at the edges
 *
 * Every consumer — the repository, the backup DTO, the UI form — would otherwise have to
 * defend against a range that points backwards or quotes more than
 * [MAX_ANNOTATION_QUOTE_CHARS] characters. A malformed range cannot be constructed, so no
 * reader has to.
 *
 * ## Why this is a `data class` and not a `@JvmInline value class`
 *
 * A JVM-inline value class carries exactly one underlying property. The four fields here
 * (attachment, start, end, quote) would have to be boxed behind a wrapper, which costs the
 * unboxing the value class exists to save and reads worse at every call site. The identity
 * this type needs is structural, so `data class` is the shape that matches.
 *
 * @param attachmentId the attachment whose text this range addresses — mandatory, because
 *   an offset is meaningless without the document it is an offset *in*.
 * @param start first character index, inclusive.
 * @param end character index one past the last, exclusive.
 * @param quote the text at `[start, end)` at the time the annotation was written.
 */
data class TextRange(val attachmentId: AttachmentId, val start: Int, val end: Int, val quote: String) {
    init {
        require(attachmentId.value.isNotBlank()) {
            "TextRange requires an attachmentId: offsets are meaningless without a document"
        }
        require(start >= 0) { "TextRange start must be >= 0, was $start" }
        require(start < end) {
            "TextRange must be non-empty: start ($start) must be < end ($end)"
        }
        require(quote.length <= MAX_ANNOTATION_QUOTE_CHARS) {
            "TextRange quote is ${quote.length} characters; the limit is $MAX_ANNOTATION_QUOTE_CHARS"
        }
    }

    /** How many characters this range spans. */
    val length: Int get() = end - start

    /** The text this range addresses in [documentText], or `null` if it no longer fits. */
    fun slice(documentText: String): String? {
        if (start < 0 || end > documentText.length) return null
        return documentText.substring(start, end)
    }
}

/**
 * What [resolveAnchor] concluded about an annotation against the current text of its file.
 *
 * Three outcomes rather than a boolean, because the useful response differs for each: an
 * exact anchor can be highlighted silently, a quote-found anchor can be highlighted at a
 * new position, and a stale one can only be reported.
 */
sealed interface AnchorResolution {
    /** The stored offsets still hold the stored quote. */
    data class Exact(val start: Int, val end: Int) : AnchorResolution

    /** The quote is still present at a different offset — the user recognised words, not numbers. */
    data class QuoteFound(val start: Int, val end: Int) : AnchorResolution

    /** Neither: the quote is gone and the offsets address something else. */
    data object Stale : AnchorResolution
}

/**
 * Resolves [range] against the current text of its attachment.
 *
 * ## The order is the decision
 *
 * The quote is searched first; the stored offsets are then consulted to confirm where it
 * landed, and only agree to `Exact` if the first occurrence is already at them.
 *
 * An earlier version consulted the offsets first, arguing they are cheaper — no search
 * needed when the span still holds the text. That optimises the wrong question: it asks
 * which lookup is fast, not which one is faithful. The quote is what the user selected;
 * the offsets are a hint recorded beside it. Prefer the hint and an edit elsewhere in the
 * file can leave the offsets addressing text that still equals the quote while the note
 * has in fact moved, reported as `Exact`.
 *
 * The cost is stated rather than hidden: `indexOf` returns the *first* occurrence, so a
 * note made on the second copy of a repeated phrase is reported at the first. There is no
 * tie-breaker that prefers the stored offsets without reintroducing the offset-first
 * behaviour, and between the two, a note that lands on an identical sentence is much less
 * wrong than one called exact while pointing at the wrong part of the document.
 *
 * ## Stale is reported, never resolved by guessing
 *
 * The third outcome leaves the annotation visible, editable and deletable. Dropping it
 * would take the user's note with it and say nothing; moving it to wherever the offsets now
 * land would put it on text it was never about. Both are worse than saying so.
 *
 * ## Exact matching, including case
 *
 * No case folding, no whitespace tolerance. A fuzzy match would anchor a note to text the
 * user did not select, which is the same defect as offsets drifting, only less visibly.
 *
 * An empty quote matches nothing and falls through to [AnchorResolution.Stale]: it would
 * otherwise match at offset 0 in every document, turning every such annotation into a
 * falsely-exact one.
 */
fun resolveAnchor(range: TextRange, documentText: String): AnchorResolution {
    if (range.quote.isEmpty()) return AnchorResolution.Stale
    val found = documentText.indexOf(range.quote)
    if (found < 0) return AnchorResolution.Stale
    // The quote is searched first and the stored offsets are consulted to *confirm* it,
    // rather than to be consulted first. The quote is the selection the user actually
    // made; the offsets are a hint recorded beside it.
    //
    // `Exact` therefore means one specific thing: the quote's first occurrence sits at the
    // offsets the annotation already stores. That is stricter than "the offsets still hold
    // this text" — for a quote appearing twice, the offsets may well hold it and the note
    // still belongs to the second copy, so reporting `Exact` there would claim a
    // confidence the two readings do not agree on.
    val offsetsAgree = range.slice(documentText) == range.quote && found == range.start
    return if (offsetsAgree) {
        AnchorResolution.Exact(range.start, range.end)
    } else {
        AnchorResolution.QuoteFound(found, found + range.quote.length)
    }
}
