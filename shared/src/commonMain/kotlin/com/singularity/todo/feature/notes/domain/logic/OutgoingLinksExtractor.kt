package com.singularity.todo.feature.notes.domain.logic

import com.singularity.todo.core.links.InternalLinkSchemes
import com.singularity.todo.core.links.InternalLinkTarget
import com.singularity.todo.core.links.InternalLinks

/**
 * Matches an anchor whose `href` is one of the app's schemes.
 *
 * The scheme alternation is wrapped in a non-capturing group so that the capture
 * spans the whole URL — without it the first scheme would match on its own.
 */
private val LINK_REGEX = Regex(
    """<a\s[^>]*href="((?:${InternalLinkSchemes.ALL.joinToString("|")})://[^"]+)"[^>]*>""",
)

/**
 * Extracts outgoing note/task links from HTML produced by
 * [com.mohamedrejeb.richeditor.model.RichTextState.toHtml].
 *
 * The richeditor library serialises [com.mohamedrejeb.richeditor.model.RichSpanStyle.Link]
 * as `<a href="note://...">` or `<a href="task://...">`.  Walking the internal paragraph
 * tree is not possible because [com.mohamedrejeb.richeditor.model.RichParagraph] is `internal`,
 * so HTML is parsed instead — the format is deterministic and simple enough for regex.
 *
 * ## What is read here and what is read in `core`
 *
 * This file knows the *shape of the editor's output* — that a link is an anchor with
 * an `href`. It does not know what a scheme is or what an identifier may contain:
 * that is [InternalLinks]'s job, and it is applied to every candidate this file
 * produces. The duplicate of this file, in the notes package, was removed — it
 * differed only in the type it returned.
 *
 * @param html the editor's HTML.
 * @return the targets, in the order they appear, each one only once.
 */
fun extractOutgoingLinks(html: String): List<InternalLinkTarget> {
    val seen = mutableSetOf<InternalLinkTarget>()
    val found = mutableListOf<InternalLinkTarget>()
    for (match in LINK_REGEX.findAll(html)) {
        val target = InternalLinks.parse(match.groupValues[1]) ?: continue
        if (seen.add(target)) found += target
    }
    return found
}
