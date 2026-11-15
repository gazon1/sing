package com.singularity.todo.core.linkpreview

/**
 * Fetches bytes from the web, under the rules a preview needs.
 *
 * ## Why this is a port-shaped interface
 *
 * The rules are the point, not the transport: which schemes may be fetched, which
 * addresses may be reached, how large a response may be, whether a redirect is
 * allowed and where it points. Those are decided once, in the one implementation,
 * and the decision has to hold for a page **and** for its preview image — an image
 * address comes from the page, so it is just as untrusted as the page's own text.
 */
interface PageSource {
    /**
     * Fetches [url], or fails with a [PreviewFetchException].
     *
     * @param maxBytes the most bytes to read. An implementation must not read the
     *   whole of a response that exceeds this.
     * @param kind which media types are acceptable. A page that is not HTML, or an
     *   image that is not an image, is a refusal rather than a partial read.
     */
    suspend fun fetch(
        url: String,
        maxBytes: Int,
        kind: FetchKind,
    ): FetchedPage
}

/** What a fetch is for. The acceptable media types follow from it. */
enum class FetchKind {
    /** A page: `text/html` or `application/xhtml+xml`. */
    Html,

    /** A preview image: any `image/…` type. */
    Image,
}

/** What a fetch produced, after its limits were applied. */
class FetchedPage(
    /** The address the response finally came from, after redirects. */
    val finalUrl: String,
    /** The response body, cut at the size limit. */
    val bytes: ByteArray,
    /** The declared media type, lowercased, without parameters — or null. */
    val contentType: String?,
)

/** Why a preview could not be produced. */
class PreviewFetchException(message: String, cause: Throwable? = null) : Exception(message, cause)
