package com.singularity.todo.core.linkpreview

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.model.MetaData

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
 * ## What is the parser's job, and what is this file's
 *
 * The *markup* is [Ksoup]'s: which tag holds which attribute, what a malformed tag
 * means, how an entity is decoded, what attribute order and quoting are allowed.
 * Those have answers the HTML standard already writes down, and a hand-rolled scanner
 * only approximates them.
 *
 * The *policy* is this file's, because it is a decision about previews and not about
 * HTML:
 *
 * 1. `og:title` → `twitter:title` → `<title>`.
 * 2. `og:description` → `twitter:description` → `meta[name=description]`.
 * 3. `og:image` → `twitter:image`.
 * 4. `og:site_name` → null; the card falls back to the page's host.
 *
 * The image and the canonical address are made absolute against the page they came
 * from: [Ksoup] reports them exactly as written, and a relative address is
 * meaningless to an image loader.
 *
 * ## What it deliberately does not do
 *
 * It reads the page's head and nothing else — no body text, no scripts, nothing that
 * would make it a reader. A page is untrusted input and the only thing that leaves
 * here is a handful of strings. `ksoup-network` is not on the classpath on purpose:
 * the fetch is our own guarded client's business, and a second network path would be
 * a second place the address checks could be forgotten.
 */
object PageMetadataParser {
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
        val meta =
            runCatching { Ksoup.parseMetaData(html, baseUrl) }.getOrNull()
                ?: return PageMetadata(null, null, null, null)
        val base = meta.canonical?.let { PreviewUrls.resolve(it, baseUrl) } ?: baseUrl
        return PageMetadata(
            title = clean(meta.ogTitle ?: meta.twitterTitle ?: meta.htmlTitle ?: meta.title),
            description = clean(meta.ogDescription ?: meta.twitterDescription ?: meta.description),
            siteName = clean(meta.ogSiteName),
            imageUrl = imageOf(meta)?.let { PreviewUrls.resolve(it, base) },
        )
    }

    /** The first image the page states, as written — before it is made absolute. */
    private fun imageOf(meta: MetaData): String? = (meta.ogImage ?: meta.twitterImage)?.takeIf { it.isNotBlank() }

    /**
     * A field as the card shows it: trimmed, with runs of whitespace collapsed.
     *
     * The parser has already decoded the entities, so this is only about the shape a
     * sentence takes when it has been wrapped across lines in the source.
     */
    private fun clean(raw: String?): String? = raw?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() }

    private val WHITESPACE = Regex("""\s+""")
}
