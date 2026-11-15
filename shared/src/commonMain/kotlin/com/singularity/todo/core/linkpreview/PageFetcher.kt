package com.singularity.todo.core.linkpreview

import com.singularity.todo.core.error.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * The one page fetcher, and the rules it enforces.
 *
 * ## What it accepts
 *
 * Only `http` and `https`, only a public address, only an HTML response, and only
 * a bounded amount of it. Redirects are followed **by hand**, up to
 * [MAX_REDIRECTS], because every hop is a new address and every address has to be
 * judged: a public page that answers `302 Location: http://127.0.0.1/` is the
 * attack this exists for, and a client that follows redirects by itself would
 * never ask.
 *
 * ## What it never sends
 *
 * No cookies, no credentials, no application headers. The request is a bare `GET`
 * with a `User-Agent` that says what it is, so a site can recognise the preview
 * request and so nothing the user is signed into travels with it — this is an app
 * that holds an auth token for its own backend, and none of that belongs at a
 * stranger's site.
 *
 * ## What it never stores
 *
 * It returns the bytes it was allowed to read and nothing else; the caller parses
 * them and drops them. No HTML is persisted anywhere.
 *
 * @param client the shared client. Its timeouts apply; this class adds none, so the
 *   process has one answer to "how long may a request take" and not two.
 * @param addresses resolves host names, so the SSRF check can see the address behind
 *   a name.
 */
class PageFetcher(client: HttpClient, private val addresses: HostAddressResolver) : PageSource {
    /**
     * The client, with redirects **off**.
     *
     * This is the one rule a caller cannot be trusted to configure: Ktor follows
     * redirects by default, and a client that follows them itself jumps to the
     * `Location` without asking this class — which is exactly the jump the per-hop
     * address check exists to stop. The policy therefore lives here rather than at the
     * call site. `config` reuses the same engine, so there is still one connection pool.
     */
    private val client = client.config { followRedirects = false }

    override suspend fun fetch(
        url: String,
        maxBytes: Int,
        kind: FetchKind,
    ): FetchedPage =
        runCatchingCancellable { fetchFollowingRedirects(url, maxBytes, kind) }
            .getOrElse { failure ->
                throw failure as? PreviewFetchException
                    ?: PreviewFetchException("The preview could not be fetched", failure)
            }

    /**
     * Follows the redirect chain, judging each hop before it is requested.
     *
     * A `repeat` rather than a `while (true)`: the number of hops is the bound, and
     * writing it as the loop's own limit means "too many" is the loop ending rather
     * than a counter checked by hand.
     */
    private suspend fun fetchFollowingRedirects(
        url: String,
        maxBytes: Int,
        kind: FetchKind,
    ): FetchedPage {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            refusalReason(current)?.let { reason -> throw PreviewFetchException(reason) }
            val response =
                client.get(current) {
                    headers.append(HttpHeaders.UserAgent, USER_AGENT)
                }
            val location = response.redirectTo()
            if (location == null) return readPage(response, current, maxBytes, kind).getOrThrow()
            current = resolveLocation(location, current)
        }
        throw PreviewFetchException("Too many redirects for the preview address")
    }

    /**
     * Why [url] may not be fetched, or null when it may.
     *
     * The scheme is checked first, because that is a property of the text; the host
     * is then checked against what it resolves to. A literal is judged as it stands
     * and a name is judged by every address it has — a name answering with one
     * public and one loopback address is refused, because the connection may land
     * on either.
     *
     * A reason rather than a throw: the caller has one place that turns a refusal
     * into the exception, so there is one place to read what a refusal means.
     */
    private suspend fun refusalReason(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        // Credentials in an address are somebody's login. A preview has no use for them,
        // and this app holds a token for its own backend — none of that belongs at a
        // stranger's site. Ktor does not turn them into an `Authorization` header today,
        // but "the client happens not to" is not a reason to pass them along.
        val carriesCredentials = url.substringAfter("://").substringBefore('/').contains('@')
        val host = PreviewUrls.host(url)
        val resolved = if (host.isBlank() || PublicAddresses.isLiteral(host)) emptyList() else addresses.resolve(host)
        return when {
            scheme != "http" && scheme != "https" -> "Only http and https addresses are previewed"
            carriesCredentials -> "The address carries credentials"
            host.isBlank() -> "The address names no host"
            !PublicAddresses.isFetchable(host, resolved) -> "The address is not a public one"
            else -> null
        }
    }

    /** The `Location` of a redirect response, or null when the response is not one. */
    private fun HttpResponse.redirectTo(): String? {
        val code = status.value
        if (code !in 300..399) return null
        if (status == HttpStatusCode.NotModified) return null
        return headers[HttpHeaders.Location]?.takeIf { it.isNotBlank() }
    }

    /** [location] as an absolute address, resolved against the address it came from. */
    private fun resolveLocation(
        location: String,
        from: String,
    ): String = PreviewUrls.resolve(location, from) ?: throw PreviewFetchException("A redirect names no address")

    /**
     * Reads the response body under the size limit.
     *
     * The declared length is checked first, so an honest large response is refused
     * without reading it; then at most [maxBytes] plus one byte is read, and one
     * byte over the limit means the body is larger than allowed. That extra byte is
     * what tells "exactly at the limit" apart from "over it" without reading the
     * whole of a body that could be gigabytes.
     *
     * @return the page, or the reason the response is not one.
     */
    private suspend fun readPage(
        response: HttpResponse,
        url: String,
        maxBytes: Int,
        kind: FetchKind,
    ): Result<FetchedPage> {
        val contentType =
            response.headers[HttpHeaders.ContentType]
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val reason =
            when {
                response.status.value !in 200..299 -> "The page answered ${response.status.value}"
                !kind.accepts(contentType) -> "The response is not what a preview reads ($contentType)"
                declared != null && declared > maxBytes -> TOO_LARGE
                else -> null
            }
        if (reason != null) return Result.failure(PreviewFetchException(reason))

        val buffer = response.bodyAsChannel().readBuffer((maxBytes + 1).toLong())
        val bytes = buffer.readByteArray()
        return if (bytes.size > maxBytes) {
            Result.failure(PreviewFetchException(TOO_LARGE))
        } else {
            Result.success(FetchedPage(finalUrl = url, bytes = bytes, contentType = contentType))
        }
    }

    /**
     * Whether [contentType] is something a preview may read.
     *
     * A server that declares nothing is accepted for a page — some pages do not say,
     * and the parser decides for itself whether the bytes are a page — but an image
     * must say it is one, because an image address is what the page chose and the
     * bytes are about to be shown.
     */
    private fun FetchKind.accepts(contentType: String?): Boolean =
        when (this) {
            FetchKind.Html -> {
                contentType == null || contentType == "text/html" || contentType == "application/xhtml+xml"
            }

            FetchKind.Image -> {
                contentType != null && contentType.startsWith("image/")
            }
        }

    private companion object {
        /** How many hops a preview may take. Three is enough for `http`→`https`→`www`. */
        const val MAX_REDIRECTS = 3

        /** The one message a response over the limit gets, from either check. */
        const val TOO_LARGE = "The response is larger than a preview reads"

        /**
         * What the request says it is.
         *
         * A site can recognise the preview request and answer it as one, and the value
         * names the app rather than pretending to be a browser.
         */
        const val USER_AGENT = "SingularityTodoLinkPreview/1.0"
    }
}
