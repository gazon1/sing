package com.singularity.todo.core.ui.text

import com.singularity.todo.core.links.InternalLinkTarget
import com.singularity.todo.core.links.InternalLinks

/**
 * A link the user can tap.
 *
 * The two cases are different kinds of thing, not two spellings of one: a [Web] link
 * goes out of the app, an [Internal] link goes somewhere else inside it. Keeping them
 * apart here means the caller never has to look at a scheme to decide what to do.
 */
sealed interface TextLink {

    /**
     * A link to the web.
     *
     * @property url the address to open. It is always absolute — a `www.` link is
     *   normalised to `https://` before it gets here.
     */
    data class Web(val url: String) : TextLink

    /**
     * A link the app owns.
     *
     * @property target what the link points at. The scheme says which kind of thing
     *   it is; the identifier says which one.
     */
    data class Internal(val target: InternalLinkTarget) : TextLink
}

/**
 * A link found in a piece of text: where it is and what it is.
 *
 * @property range the half-open range of the matched text.
 * @property link what the link is.
 */
data class TextLinkSpan(val range: IntRange, val link: TextLink)

/**
 * Finds the links in a piece of plain text.
 *
 * ## What counts as a link
 *
 * A web link is a `http://` or `https://` address, or a `www.` one — normalised to
 * `https://`. An internal link is one of the app's own schemes, read through
 * [InternalLinks] so that the rules about what an identifier may contain are the
 * same ones the rest of the app uses.
 *
 * ## What is deliberately not a link
 *
 * `3.14`, `2.5.6` and `file.txt` are not links: they have no scheme. `xnote://id` is
 * not one either — the scheme belongs to somebody else, and a link that only looks
 * like ours must not be opened as ours.
 *
 * ## Trailing punctuation
 *
 * A sentence that ends with a URL ends with a full stop, and the full stop is not
 * part of the address. Punctuation is trimmed from the end of a web link. A closing
 * bracket is trimmed only when the text has more closing brackets than opening ones
 * — otherwise `(see https://example.com)` would lose the bracket that belongs to the
 * sentence.
 */
object TextLinkDetector {

    private val WEB_REGEX = Regex("""(?i)\b(?:https?://|www\.)\S+""")

    /** Punctuation that is never part of an address. */
    private const val ALWAYS_TRAILING = ".,;:!?\"'»"

    /** Closing brackets and the opening bracket each one pairs with. */
    private const val CLOSING_BRACKETS = ")]}"
    private const val OPENING_BRACKETS = "([{"

    private const val WWW = "www."
    private const val HTTPS = "https://"

    /**
     * Finds every link in [text].
     *
     * The links come back in the order they appear and never overlap: when a web
     * address contains something that looks like an internal link, the address wins,
     * because the address is the longer thing the user pointed at.
     *
     * @param text the text to scan.
     */
    fun detect(text: String): List<TextLinkSpan> {
        val candidates = webSpans(text) + internalSpans(text)
        val accepted = mutableListOf<TextLinkSpan>()
        for (span in candidates.sortedWith(compareBy({ it.range.first }, { -it.range.last }))) {
            val overlaps = accepted.any { it.range.first <= span.range.last && span.range.first <= it.range.last }
            if (!overlaps) accepted += span
        }
        return accepted
    }

    private fun webSpans(text: String): List<TextLinkSpan> =
        WEB_REGEX.findAll(text).mapNotNull { match ->
            val address = trimTrailing(match.value)
            if (address.isEmpty()) return@mapNotNull null
            val url = if (address.startsWith(WWW, ignoreCase = true)) HTTPS + address else address
            TextLinkSpan(match.range.first..(match.range.first + address.length - 1), TextLink.Web(url))
        }.toList()

    private fun internalSpans(text: String): List<TextLinkSpan> =
        InternalLinks.findIn(text).map { TextLinkSpan(it.range, TextLink.Internal(it.target)) }

    /**
     * Removes the punctuation that follows an address.
     *
     * A closing bracket is only removed when it has no partner inside the address —
     * the bracket in `(https://example.com)` closes a bracket that opened before
     * the address, and dropping it would leave the sentence unbalanced.
     */
    private fun trimTrailing(address: String): String {
        var end = address.length
        while (end > 0) {
            val last = address[end - 1]
            when {
                ALWAYS_TRAILING.contains(last) -> end--

                CLOSING_BRACKETS.contains(last) -> {
                    val opening = OPENING_BRACKETS[CLOSING_BRACKETS.indexOf(last)]
                    val inAddress = address.substring(0, end)
                    if (inAddress.count { it == last } > inAddress.count { it == opening }) {
                        end--
                    } else {
                        return address.substring(0, end)
                    }
                }

                else -> return address.substring(0, end)
            }
        }
        return address.substring(0, end)
    }
}
