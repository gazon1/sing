package com.singularity.todo.core.linkpreview

/**
 * The metadata a page publishes about itself.
 *
 * @property title the page's title, or null when it states none.
 * @property description the page's own description, or null.
 * @property siteName what the publisher calls itself, or null.
 * @property imageUrl the preview image, already resolved to an absolute address.
 */
data class PageMetadata(val title: String?, val description: String?, val siteName: String?, val imageUrl: String?)

/**
 * Reads a page's own description of itself out of its HTML.
 *
 * ## What it reads
 *
 * Open Graph first — `og:title`, `og:description`, `og:image`, `og:site_name`,
 * `og:url` — then the standard HTML fallbacks: `<title>`, `name="description"`,
 * `<link rel="canonical">`. Open Graph wins where both are present because it is
 * the one the publisher wrote *for* a preview card.
 *
 * ## What it deliberately does not do
 *
 * It is a scanner, not an HTML parser, and it does not pretend to be one: it looks
 * at the tags it needs and ignores everything else. It never executes anything,
 * never follows a script, and reads no text beyond the fields above — a page is
 * untrusted input and the only thing that leaves here is a handful of strings.
 *
 * A page with no usable metadata yields nulls, and the caller decides what to show.
 */
object PageMetadataParser {
    private val META = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val LINK = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TITLE =
        Regex(
            """<title\b[^>]*>(.*?)</title\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val ATTRIBUTE = Regex("""([\w:-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Reads [html] against the page's own address [baseUrl].
     *
     * @param html the page's source, as fetched.
     * @param baseUrl the address the page was finally served from, after redirects.
     *   Relative image and canonical addresses are resolved against it.
     */
    fun parse(
        html: String,
        baseUrl: String,
    ): PageMetadata {
        val props = attributesByKey(html)
        val base = props["og:url"]?.let { PreviewUrls.resolve(it, baseUrl) } ?: baseUrl
        val image = props["og:image"] ?: props["twitter:image"]
        return PageMetadata(
            title = firstNonBlank(props["og:title"], documentTitle(html)),
            description = firstNonBlank(props["og:description"], props["description"]),
            siteName = firstNonBlank(props["og:site_name"]),
            imageUrl = image?.let { PreviewUrls.resolve(it, base) },
        )
    }

    /** The page title from `<title>`, decoded and trimmed. */
    private fun documentTitle(html: String): String? =
        TITLE
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let(::clean)

    /**
     * The `content` values of the page's metadata, by key.
     *
     * Keys are the `property` or `name` of a meta tag, lowercased. A repeated key
     * keeps the first value: a page that states a title twice stated the first one
     * first. The canonical link is read too, as the fallback base address.
     */
    private fun attributesByKey(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (tag in META.findAll(html)) {
            val attrs = attributes(tag.value)
            val key = (attrs["property"] ?: attrs["name"])?.lowercase()
            val content = attrs["content"]?.let(::clean)
            if (key != null && !content.isNullOrEmpty()) result.putIfAbsent(key, content)
        }
        for (tag in LINK.findAll(html)) {
            val attrs = attributes(tag.value)
            val isCanonical = attrs["rel"].orEmpty().equals("canonical", ignoreCase = true)
            val href = attrs["href"]
            if (isCanonical && href != null && result["og:url"] == null) {
                result.putIfAbsent("og:url", href)
            }
        }
        return result
    }

    /** The quoted attributes of one tag, by lowercased name. */
    private fun attributes(tag: String): Map<String, String> =
        ATTRIBUTE
            .findAll(tag)
            .mapNotNull { match ->
                val name = match.groupValues[1].lowercase()
                val value = match.groupValues[3].ifEmpty { match.groupValues[4] }
                name to value
            }.toMap()

    /** The first of [candidates] that is not blank. */
    private fun firstNonBlank(vararg candidates: String?): String? = candidates.firstOrNull { !it.isNullOrBlank() }

    /** Trimmed, with the entities a title or description normally contains decoded. */
    private fun clean(raw: String): String =
        raw
            .replace(WHITESPACE, " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
}
