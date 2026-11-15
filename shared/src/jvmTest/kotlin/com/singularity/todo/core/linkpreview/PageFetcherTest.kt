package com.singularity.todo.core.linkpreview

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The rules a preview fetch obeys, exercised against a client whose answers the test
 * chooses.
 *
 * Every case here is a way a page could try to make the app do something it should not:
 * redirect into the local network, redirect forever, answer with a body that never ends,
 * answer with something that is not a page. The allowed case is there too, so the rules
 * are shown to permit the ordinary thing.
 */
@Tag("fast")
class PageFetcherTest {
    /** A resolver whose answer the test chooses, so no DNS is asked. */
    private class FixedResolver(private val address: String = "93.184.216.34") : HostAddressResolver {
        override suspend fun resolve(host: String): List<String> = listOf(address)
    }

    /**
     * A client that *does* follow redirects.
     *
     * Deliberately the opposite of what the fetcher wants: the fetcher must take the
     * policy over, or a caller that builds its own client would hand it one that jumps
     * to a `Location` without any address check — the SSRF the rules exist to stop.
     */
    private fun client(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = true
            install(HttpTimeout)
        }

    private fun fetcher(
        engine: MockEngine,
        address: String = "93.184.216.34",
    ) = PageFetcher(client(engine), FixedResolver(address))

    @Test
    fun `an ordinary html page is returned`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "<html><title>Hi</title></html>",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
                    )
                }
            val page = fetcher(engine).fetch("https://example.com/a", 1024, FetchKind.Html)
            assertTrue(page.bytes.decodeToString().contains("Hi"))
            assertEquals("text/html", page.contentType)
        }

    @Test
    fun `a non-http scheme is refused before a request is made`() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.OK) }
            assertFailsWith<PreviewFetchException> {
                fetcher(engine).fetch("ftp://example.com/a", 1024, FetchKind.Html)
            }
            assertEquals(0, engine.requestHistory.size, "no request may be made for a refused scheme")
        }

    @Test
    fun `a private address is refused before a request is made`() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.OK) }
            assertFailsWith<PreviewFetchException> {
                fetcher(engine, address = "127.0.0.1").fetch("http://localhost/a", 1024, FetchKind.Html)
            }
            assertEquals(0, engine.requestHistory.size)
        }

    @Test
    fun `a redirect into the local network is refused, and the target is not requested`() =
        runTest {
            val engine =
                MockEngine { request ->
                    if (request.url.host == "example.com") {
                        respond(
                            content = "",
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "http://127.0.0.1/secret"),
                        )
                    } else {
                        respondError(HttpStatusCode.OK)
                    }
                }
            val resolver =
                object : HostAddressResolver {
                    override suspend fun resolve(host: String): List<String> =
                        if (host == "127.0.0.1") listOf("127.0.0.1") else listOf("93.184.216.34")
                }
            val page = PageFetcher(client(engine), resolver)
            assertFailsWith<PreviewFetchException> { page.fetch("https://example.com/a", 1024, FetchKind.Html) }
            assertTrue(
                engine.requestHistory.none { it.url.host == "127.0.0.1" },
                "the private target must not be requested at all",
            )
        }

    @Test
    fun `a redirect chain longer than the limit is refused`() =
        runTest {
            val engine =
                MockEngine { request ->
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://example.com/${request.url.encodedPath}"),
                    )
                }
            assertFailsWith<PreviewFetchException> {
                fetcher(engine).fetch("https://example.com/0", 1024, FetchKind.Html)
            }
        }

    @Test
    fun `a body larger than the limit is refused, from the declared length`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "x".repeat(4096),
                        status = HttpStatusCode.OK,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("text/html"),
                                HttpHeaders.ContentLength to listOf("4096"),
                            ),
                    )
                }
            assertFailsWith<PreviewFetchException> {
                fetcher(engine).fetch("https://example.com/a", 1024, FetchKind.Html)
            }
        }

    @Test
    fun `a body larger than the limit is refused when no length was declared`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "x".repeat(4096),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }
            assertFailsWith<PreviewFetchException> {
                fetcher(engine).fetch("https://example.com/a", 1024, FetchKind.Html)
            }
        }

    @Test
    fun `a response that is not html is refused`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "%PDF-1.7",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/pdf"),
                    )
                }
            assertFailsWith<PreviewFetchException> {
                fetcher(engine).fetch("https://example.com/a", 1024, FetchKind.Html)
            }
        }

    @Test
    fun `an image fetch refuses a response that is not an image`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "<html>",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }
            assertFailsWith<PreviewFetchException> {
                fetcher(engine).fetch("https://example.com/a.png", 1024, FetchKind.Image)
            }
        }

    @Test
    fun `an error status is refused`() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
            assertFailsWith<PreviewFetchException> {
                fetcher(engine).fetch("https://example.com/a", 1024, FetchKind.Html)
            }
        }

    @Test
    fun `a redirect with no location is refused rather than read as a page`() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "", status = HttpStatusCode.Found)
                }
            assertFailsWith<PreviewFetchException> {
                fetcher(engine).fetch("https://example.com/a", 1024, FetchKind.Html)
            }
        }

    @Test
    fun `a relative location is resolved against the page that sent it`() =
        runTest {
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/a" -> {
                            respond(
                                content = "",
                                status = HttpStatusCode.Found,
                                headers = headersOf(HttpHeaders.Location, "/b"),
                            )
                        }

                        else -> {
                            respond(
                                content = "<title>B</title>",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/html"),
                            )
                        }
                    }
                }
            val page = fetcher(engine).fetch("https://example.com/a", 1024, FetchKind.Html)
            assertEquals("https://example.com/b", page.finalUrl)
        }

    @Test
    fun `a cancelled fetch stays cancelled`() =
        runTest {
            // Cancellation must travel as itself. `runCatchingCancellable` is the helper
            // that guarantees it; a plain `runCatching` here would turn the cancellation
            // into a `PreviewFetchException` and the caller would keep waiting forever.
            val engine = MockEngine { throw kotlinx.coroutines.CancellationException("stop") }
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                fetcher(engine).fetch("https://example.com/a", 1024, FetchKind.Html)
            }
        }
}
