package com.singularity.todo.core.linkpreview

import com.singularity.todo.core.ui.text.TextLink
import com.singularity.todo.core.ui.text.TextLinkDetector

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
 * ## The cache key
 *
 * [keyOf] strips the fragment and lowercases the scheme and host, so the same page
 * reached as `https://Example.com/a#top` and `https://example.com/a` is one cache
 * entry. It deliberately does **not** drop the query string: `?id=1` and `?id=2`
 * are different pages on most sites, and merging them would show the wrong title.
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
     */
    fun keyOf(url: String): String {
        val hash = url.indexOf('#')
        val withoutFragment = if (hash >= 0) url.substring(0, hash) else url
        val schemeEnd = withoutFragment.indexOf("://")
        if (schemeEnd <= 0) return withoutFragment
        val scheme = withoutFragment.substring(0, schemeEnd).lowercase()
        val rest = withoutFragment.substring(schemeEnd + 3)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd).lowercase()
        return "$scheme://$authority${rest.substring(authorityEnd)}"
    }

    /**
     * The host of [url], for the label a preview shows when the page names no site.
     *
     * Empty when the address has no host, so the caller can fall back further
     * rather than print a fragment of a broken address.
     */
    fun host(url: String): String {
        val schemeEnd = url.indexOf("://")
        val rest = if (schemeEnd >= 0) url.substring(schemeEnd + 3) else url
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.substringAfter('@').substringBefore(':')
    }

    /**
     * [ref] as an absolute address, resolved against the page address [base].
     *
     * A page names its image and its canonical address relative to itself, and a
     * relative address is meaningless to an image loader. Three shapes occur in
     * practice and each is handled: an absolute address is kept, a protocol-relative
     * one borrows the page's scheme, and anything else is joined to the page's
     * directory. Null when [ref] is blank.
     */
    fun resolve(
        ref: String,
        base: String,
    ): String? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        val scheme = base.substringBefore("://", "https").ifEmpty { "https" }
        return when {
            trimmed.isAbsolute() -> trimmed
            trimmed.startsWith("//") -> "$scheme:$trimmed"
            else -> originOf(base) + joinedPath(trimmed, base)
        }
    }

    /** Whether [ref] already names its own scheme. */
    private fun String.isAbsolute(): Boolean =
        startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

    /** The scheme and authority of [base], without its path. */
    private fun originOf(base: String): String {
        val pathStart = base.indexOf("://").let { if (it < 0) -1 else base.indexOf('/', it + 3) }
        return if (pathStart < 0) base.substringBefore('?').substringBefore('#') else base.substring(0, pathStart)
    }

    /** [ref] resolved against [base]'s path, then `.`/`..` collapsed. */
    private fun joinedPath(
        ref: String,
        base: String,
    ): String {
        // A reference that is only a query or a fragment replaces that part of the page
        // and keeps the page's own path: `?a=1` on `/p` is `/p?a=1`, not `/p/?a=1`.
        if (ref.startsWith("?") || ref.startsWith("#")) return basePathOf(base) + ref
        // A path that starts at the root does not inherit the page's directory, and
        // joining it as if it did produces `/post//img` — a different address.
        if (ref.startsWith("/")) return collapseDots(ref)
        val directory = basePathOf(base).substringBeforeLast('/', "")
        return collapseDots("$directory/$ref")
    }

    /** The path of [base], without its scheme, authority, query or fragment. */
    private fun basePathOf(base: String): String {
        val origin = originOf(base)
        return base.substring(origin.length).substringBefore('?').substringBefore('#')
    }

    /** Removes `.` and `..` from a relative path, so the joined address is a plain one. */
    private fun collapseDots(path: String): String {
        if (!path.contains("/.")) return path
        val suffixStart = path.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) path.length else it }
        val suffix = path.substring(suffixStart)
        val out = mutableListOf<String>()
        for (segment in path.substring(0, suffixStart).split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out += segment
            }
        }
        return "/" + out.joinToString("/") + suffix
    }
}
