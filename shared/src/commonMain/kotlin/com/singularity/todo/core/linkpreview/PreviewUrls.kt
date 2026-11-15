package com.singularity.todo.core.linkpreview

import com.singularity.todo.core.ui.text.TextLink
import com.singularity.todo.core.ui.text.TextLinkDetector
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom

/**
 * Which address a task title previews, and what to cache it under.
 *
 * ## The primary address
 *
 * A title may hold several addresses. The card previews **the first one that is a
 * web address**, in the order it appears in the title. The rule is the detector's
 * own order, so there is no second opinion about what an address is: the same
 * [TextLinkDetector] that underlines the links decides which one is first. The
 * other addresses are left exactly as they are — they stay tappable, and nothing
 * rewrites the title.
 *
 * An internal link (`task://…`) is never previewed: it points inside the app, and
 * fetching it would be a request to nothing.
 *
 * ## What is Ktor's job, and what is this file's
 *
 * The *shape* of a URL — scheme, authority, host, port, user info, IPv6 brackets,
 * percent-encoding — is [Url]'s, not this file's. The rules about it are this
 * file's: which schemes may be previewed, that the fragment is not part of a cache
 * key, and that a relative reference resolves against the page it was found on.
 *
 * Two places still do a little of their own work, and both for a measured reason:
 *
 * - **`..` is not collapsed by Ktor.** `URLBuilder.takeFrom("../a.png")` against
 *   `/post/deep/1` leaves `/post/deep/../a.png` in place, which is the same page
 *   under a different key. [collapseDots] is the one piece of path arithmetic kept.
 * - **A host is judged by [host], not by `Url.host`**, because that one returns the
 *   brackets of an IPv6 literal (`[::1]`), and the address check wants the address.
 */
object PreviewUrls {
    /**
     * The address to preview for [title], or null when the title has no web address.
     *
     * @param title the task title, as the user typed it.
     */
    fun primaryIn(title: String): String? =
        TextLinkDetector
            .detect(title)
            .firstNotNullOfOrNull { (it.link as? TextLink.Web)?.url }

    /**
     * The cache key of [url]: scheme and host lowercased, fragment dropped.
     *
     * A malformed address is returned unchanged — it will fail to fetch, and the
     * failure is then cached under the string the user actually wrote.
     *
     * The query is deliberately kept: `?id=1` and `?id=2` are different pages on most
     * sites, and merging them would show the wrong title.
     */
    fun keyOf(url: String): String {
        val parsed = parse(url) ?: return url.substringBefore('#')
        val origin =
            buildString {
                append(parsed.protocol.name.lowercase())
                append("://")
                append(parsed.host.lowercase())
                // A port that is the scheme's default is not part of the address: Ktor
                // reports `specifiedPort == 0` for an omitted one, and 443 for
                // `https://e.com:443` — the same page under two spellings, and one cache
                // entry. Only a port that changes the address is kept.
                if (parsed.specifiedPort != 0 && parsed.specifiedPort != parsed.protocol.defaultPort) {
                    append(':')
                    append(parsed.specifiedPort)
                }
            }
        val path = parsed.encodedPath.ifEmpty { "/" }
        return origin + path + (parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
    }

    /**
     * The host of [url], for the label a preview shows when the page names no site.
     *
     * Empty when the address has no host, so the caller can fall back further rather
     * than print a fragment of a broken address. An IPv6 literal comes back without
     * its brackets — it is an address, not a URL component.
     */
    fun host(url: String): String = parse(url)?.host?.removeSurrounding("[", "]").orEmpty()

    /**
     * [ref] as an absolute address, resolved against the page address [base].
     *
     * A page names its image and its canonical address relative to itself, and a
     * relative address means nothing to an image loader. Everything the standard
     * defines — a root-relative path, a protocol-relative one, a query-only or
     * fragment-only reference — is [URLBuilder.takeFrom]'s answer; the one thing it
     * leaves undone is the `..` in the resulting path, which is collapsed here.
     *
     * Null when [ref] is blank, or when either address is unparsable.
     */
    fun resolve(
        ref: String,
        base: String,
    ): String? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        val resolved =
            runCatching {
                URLBuilder(base).takeFrom(trimmed).buildString()
            }.getOrNull() ?: return null
        return collapseDots(resolved)
    }

    /**
     * [url] as Ktor reads it, or null when it is not an address this file will use.
     *
     * The scheme is required, and it is required as *text*, not as something Ktor
     * infers: `Url("example.com/x")` parses to host `localhost` with scheme `http`,
     * which is an address nobody wrote and a host the address check would then judge.
     * Requiring the `//` in the input means a string without an authority is refused
     * rather than parsed into someone else's idea of one.
     */
    private fun parse(url: String): Url? =
        runCatching {
            Url(url).takeIf { url.contains("://") }
        }.getOrNull()

    /**
     * Removes `.` and `..` from the path of [url], leaving the rest of it alone.
     *
     * Kept because Ktor does not do it: `takeFrom` implements RFC 3986 §5.2's
     * reference resolution but not §5.2.4's dot-segment removal, so
     * `/post/deep/../a.png` survives as itself. That is the same page as
     * `/post/a.png` and a different cache key, which is the whole reason this runs.
     *
     * An address that does not parse, or whose path holds no dot-segment, is
     * returned unchanged.
     */
    private fun collapseDots(url: String): String {
        if (!url.contains("/.")) return url
        val parsed = parse(url) ?: return url
        val path = parsed.encodedPath
        if (!path.contains("/.")) return url
        val collapsed = removeDotSegments(path)
        return buildString {
            append(parsed.protocol.name.lowercase())
            append("://")
            append(parsed.host)
            if (parsed.specifiedPort != 0) {
                append(':')
                append(parsed.specifiedPort)
            }
            append(collapsed)
            parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { append('?').append(it) }
            parsed.encodedFragment.takeIf { it.isNotEmpty() }?.let { append('#').append(it) }
        }
    }

    /**
     * RFC 3986 §5.2.4 dot-segment removal.
     *
     * Only `.` and `..` as whole segments are removed. An **empty** segment is kept: it
     * is the `//` in `/a//b`, and a server may serve that as a different resource from
     * `/a/b`. The first version dropped empty segments along with the dots, which is not
     * normalisation — it is rewriting the address into one the page did not name.
     *
     * The root is a floor, not a segment that `..` may remove: `/../a` is `/a`, not `a`,
     * and a trailing `/.` or `/..` keeps the slash that makes it a directory (so `/a/.`
     * is `/a/` and `/a/..` is `/`). Splitting on `/` and popping the last written piece
     * got both wrong — the first wrote `https://example.coma`, the second dropped the
     * final slash — so the standard's own loop is used instead.
     */
    private fun removeDotSegments(path: String): String {
        var input = path
        val output = StringBuilder()
        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> {
                    input = input.removePrefix("../")
                }

                input.startsWith("./") -> {
                    input = input.removePrefix("./")
                }

                input.startsWith("/./") -> {
                    input = "/" + input.removePrefix("/./")
                }

                input == "/." -> {
                    input = "/"
                }

                input.startsWith("/../") -> {
                    input = "/" + input.removePrefix("/../")
                    output.dropLastSegment()
                }

                input == "/.." -> {
                    input = "/"
                    output.dropLastSegment()
                }

                input == "." || input == ".." -> {
                    input = ""
                }

                else -> {
                    // Move one segment — its leading `/`, if any, up to the next `/`.
                    val start = if (input.startsWith("/")) 1 else 0
                    val next = input.indexOf('/', start)
                    val end = if (next < 0) input.length else next
                    output.append(input, 0, end)
                    input = input.substring(end)
                }
            }
        }
        return output.toString()
    }

    /** Removes the last segment of [this], leaving the slash before it in place. */
    private fun StringBuilder.dropLastSegment() {
        val slash = lastIndexOf("/")
        setLength(if (slash < 0) 0 else slash)
    }
}
