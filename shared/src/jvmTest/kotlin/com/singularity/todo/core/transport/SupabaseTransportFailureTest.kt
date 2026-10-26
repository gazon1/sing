package com.singularity.todo.core.transport

import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.utils.EmptyContent
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.util.Attributes
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The facts a vendor failure carries, read at the seam.
 *
 * ## What was untested, and why it mattered
 *
 * `facts()` is where the server's own code is lifted out of the SDK's exception
 * for the layers above. It read PostgREST's code and nothing else, so a storage or
 * auth failure — whose code is not a PostgREST code at all — arrived at the
 * classification with an empty one, and a failure nobody could act on. The fallback
 * to `RestException.error` is the fix, and this is the test that holds it.
 *
 * ## Why the response is real
 *
 * `RestException` builds part of its message from the HTTP response it carries, so
 * the response has to be a genuine one — a hand-made object would have to answer the
 * request as well. The stub engine answers with the status the test asks for and
 * nothing else, so nothing is invented and nothing reaches the network. Same shape
 * the auth seam's tests use.
 */
@Tag("fast")
class SupabaseTransportFailureTest {

    /**
     * A storage failure carries its code in `error`, not in a PostgREST code.
     *
     * This is the case that used to lose its code: the exception is a `RestException`,
     * not a `PostgrestRestException`, so `facts()` reported no server code and the
     * failure arrived as "something went wrong".
     */
    @Test
    fun `a storage failure keeps the server's own code`() {
        val error = RestException(
            error = "Bucket not found",
            description = "The bucket does not exist",
            response = StubResponse(HttpStatusCode.NotFound),
        )

        val facts = SupabaseTransportFailure.facts(error)

        assertEquals(404, facts.statusCode)
        assertEquals(
            "Bucket not found",
            facts.serverCode,
            "the server's own code has to survive a failure that is not PostgREST's",
        )
        assertEquals("The bucket does not exist", facts.detail)
    }

    /**
     * A PostgREST failure keeps its own code, and it wins over the error string.
     *
     * PostgREST puts `PGRST202` in `code` and a human sentence in `error`; the code
     * is the machine-readable half and the one the dashboard groups by, so it is the
     * one that has to be reported.
     */
    @Test
    fun `a PostgREST code wins over the error string`() {
        val error = PostgrestRestException(
            message = "Could not find the function",
            hint = "The function is not in the schema cache",
            details = JsonNull,
            code = "PGRST202",
            response = StubResponse(HttpStatusCode.NotFound),
        )

        val facts = SupabaseTransportFailure.facts(error)

        assertEquals(404, facts.statusCode)
        assertEquals("PGRST202", facts.serverCode)
    }

    /** A failure that says nothing still reports what it has, and invents nothing. */
    @Test
    fun `a failure with no code reports none`() {
        val facts = SupabaseTransportFailure.facts(IllegalStateException("the socket gave up"))

        assertEquals(null, facts.statusCode)
        assertEquals(null, facts.serverCode)
        assertEquals("the socket gave up", facts.detail, "the message is all this failure has")
    }
}

/** A response real enough for the SDK's exception to read — nothing reaches the network. */
@OptIn(InternalAPI::class)
private class StubResponse(override val status: HttpStatusCode) : HttpResponse() {
    override val call: HttpClientCall = stubCall(status)
    override val version: HttpProtocolVersion get() = HttpProtocolVersion.HTTP_1_1
    override val requestTime: GMTDate get() = GMTDate(0)
    override val responseTime: GMTDate get() = GMTDate(0)
    override val rawContent: ByteReadChannel get() = ByteReadChannel.Empty
    override val headers: Headers get() = Headers.Empty
    override val coroutineContext: CoroutineContext get() = EmptyCoroutineContext
}

@OptIn(InternalAPI::class)
private fun stubCall(status: HttpStatusCode): HttpClientCall {
    val client = HttpClient(StubEngine(status))
    val request = HttpRequestData(
        url = Url("https://project.supabase.co/storage/v1/object/backups/a.zip"),
        method = HttpMethod.Post,
        headers = Headers.Empty,
        body = EmptyContent,
        executionContext = Job(),
        attributes = Attributes(),
    )
    val response = HttpResponseData(
        statusCode = status,
        requestTime = GMTDate(0),
        headers = Headers.Empty,
        version = HttpProtocolVersion.HTTP_1_1,
        body = ByteReadChannel.Empty,
        callContext = EmptyCoroutineContext,
    )
    return HttpClientCall(client, request, response)
}

/** An engine that answers every request with one status, and nothing else. */
@OptIn(InternalAPI::class)
private class StubEngine(private val status: HttpStatusCode) : HttpClientEngineBase("stub") {
    override val config: HttpClientEngineConfig = HttpClientEngineConfig()

    override suspend fun execute(data: HttpRequestData): HttpResponseData = HttpResponseData(
        statusCode = status,
        requestTime = GMTDate(0),
        headers = Headers.Empty,
        version = HttpProtocolVersion.HTTP_1_1,
        body = ByteReadChannel.Empty,
        callContext = EmptyCoroutineContext,
    )
}
