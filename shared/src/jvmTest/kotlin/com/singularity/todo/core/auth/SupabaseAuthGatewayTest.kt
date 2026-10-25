package com.singularity.todo.core.auth

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import com.singularity.todo.core.ids.UserId
import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.url
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam, against a fake provider.
 *
 * Three claims live here, and none of them could be claimed before: that a magic
 * link does not create an account, that a magic-link token becomes a session, and
 * that a refresh the provider rejects is reported as a rejection — a different
 * answer from a failure to reach the provider, and the only difference that keeps
 * a user signed in on a train.
 */
@Tag("fast")
class SupabaseAuthGatewayTest {

    private val secure = MapSecureStorage()
    private val legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev"))
    private val store = SecureSessionManager(log = testLogger(), secure = secure, legacy = legacy)
    private val plugin = FakeSupabaseAuth(store)

    private val gateway = SupabaseAuthGateway(
        clients = object : SupabaseClientProvider(
            resolver = SupabaseConfigResolver(idGenerator = FixedIdGenerator(), buildTime = { null }),
            store = secure,
            sessionStore = store,
        ) {
            override suspend fun auth(): Auth? = plugin
        },
        log = testLogger(),
    )

    @Test
    fun `a magic link to an unknown address creates no account`() = runTest {
        val result = gateway.sendMagicLink("a@b.c")

        assertTrue(result.isSuccess, "the provider refused the link: ${result.exceptionOrNull()}")
        assertEquals("a@b.c", plugin.lastOtpEmail)
        assertEquals(
            false,
            plugin.lastOtpCreateUser,
            "the link must ask for a link to an *existing* account, not for one to be created",
        )
    }

    @Test
    fun `a magic link token is exchanged for a session`() = runTest {
        plugin.verifyResult = OtpVerifyResult.Authenticated(providerSession())

        val result = gateway.verifyMagicLink("a@b.c", "token-1")

        val session = result.getOrThrow()
        assertEquals("owner-1", session.userId)
        assertEquals("a@b.c", session.email)
        assertEquals("access-1", session.accessToken)
        assertEquals("refresh-1", session.refreshToken)
    }

    @Test
    fun `a rejected refresh token is reported as a rejection`() = runTest {
        plugin.refreshFailure = AuthRestException(
            errorCode = "invalid_grant",
            errorDescription = "Invalid Refresh Token",
            response = StubResponse(HttpStatusCode.BadRequest),
        )

        val result = gateway.refresh("access-1", "refresh-1")

        assertTrue(result.isSuccess, "a rejection is an answer, not a failure")
        assertNull(result.getOrThrow(), "the rejection is the null the repository acts on")
    }

    @Test
    fun `a provider that is unwell does not end the session`() = runTest {
        plugin.refreshFailure = AuthRestException(
            errorCode = "unexpected_failure",
            errorDescription = "The provider is unwell",
            response = StubResponse(HttpStatusCode.InternalServerError),
        )

        val result = gateway.refresh("access-1", "refresh-1")

        assertTrue(result.isFailure, "a server that is unwell is not a rejection")
    }

    @Test
    fun `a transport failure does not end the session`() = runTest {
        // The plugin's own answer to a provider it could not reach: the SDK wraps
        // every transport failure in this exception, and the seam tells it apart
        // from a status the provider chose.
        plugin.refreshFailure = HttpRequestException(
            "offline",
            HttpRequestBuilder().apply { url("https://project.supabase.co/auth/v1/token") },
        )

        val result = gateway.refresh("access-1", "refresh-1")

        assertTrue(result.isFailure, "a failure to reach the provider is not a rejection")
    }

    @Test
    fun `the live session is read from the credential store and handed to the plugin`() = runTest {
        store.save(Session.SignedIn(UserId.fromString("owner-1"), "a@b.c", "access-1", "refresh-1"))

        val session = gateway.currentSession()

        assertEquals("owner-1", session?.userId)
        assertEquals("access-1", session?.accessToken)
        assertEquals(
            1,
            plugin.importSessions.size,
            "the plugin is told who is signed in, or its own requests go out as a stranger",
        )
    }

    @Test
    fun `a device with no session has none to restore`() = runTest {
        assertNull(gateway.currentSession(), "no session is an ordinary state, not a failure")
    }

    private fun providerSession() = UserSession(
        accessToken = "access-1",
        refreshToken = "refresh-1",
        expiresIn = 3600L,
        tokenType = "bearer",
        user = UserInfo(aud = "", id = "owner-1", email = "a@b.c"),
    )
}

/**
 * The provider's answer, as the SDK's exception carries it.
 *
 * The SDK's exception holds the HTTP response and builds its message out of the
 * request inside it — the URL, the headers and the method of the call that produced
 * it. So the response has to be a real one: a hand-made response would have to
 * answer the request as well, and there is no honest way to fake a call that was
 * never made.
 */
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

/**
 * A call the SDK's exception can read.
 *
 * The client is a real one over an engine that answers every request with the
 * status the test wants: nothing reaches the network, and the call is a genuine
 * one — the request it carries is the request the refresh would have made.
 */
@OptIn(InternalAPI::class)
private fun stubCall(status: HttpStatusCode): HttpClientCall {
    val client = HttpClient(StubEngine(status))
    val request = HttpRequestData(
        url = Url("https://project.supabase.co/auth/v1/token"),
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

/**
 * An engine that answers every request with one status.
 *
 * It exists so that the response the SDK's exception carries is a real response,
 * without a server behind it: the status is the one the test asked for, and
 * nothing else about the answer is invented.
 */
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
