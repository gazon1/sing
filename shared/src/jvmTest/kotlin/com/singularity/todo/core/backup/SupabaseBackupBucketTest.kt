package com.singularity.todo.core.backup

import com.singularity.todo.core.auth.DataStoreSessionStore
import com.singularity.todo.core.auth.FixedIdGenerator
import com.singularity.todo.core.auth.MapSecureStorage
import com.singularity.todo.core.auth.SecureSessionManager
import com.singularity.todo.core.auth.SupabaseClientProvider
import com.singularity.todo.core.auth.SupabaseConfigResolver
import com.singularity.todo.core.auth.newPreferencesStore
import com.singularity.todo.core.auth.testLogger
import com.singularity.todo.core.error.TransportFailure
import com.singularity.todo.core.files.ByteArraySink
import com.singularity.todo.core.files.ByteArraySource
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real adapter, against a Supabase Storage that answers the test's terms.
 *
 * The claims are [BackupBucketContract]'s — the very same ones the in-memory
 * fake is held to — so the fake cannot be right where the adapter is wrong.
 *
 * ## Why the server is a fake and not a project
 *
 * A live project would make these claims depend on somebody's account, on a
 * bucket that exists, and on a network: the test would run in no ordinary
 * checkout. The storage REST shape is small — three calls — and answering it
 * here keeps the claims about *this* adapter: that it uploads with a replace,
 * that it writes the stored bytes into the sink untouched, that a listing is
 * scoped to the prefix, and that a failure arrives as the class it belongs to.
 *
 * A live stack is still worth running once — `docs/architecture/local-stack.md`
 * says how — but a claim that only a live stack can check is a claim that
 * nobody checks on an ordinary day.
 */
@Tag("fast")
class SupabaseBackupBucketTest {
    private val storage = FakeSupabaseStorage()
    private val bucket = SupabaseBackupBucket(provider(storage.client()))
    private val contract = BackupBucketContract(bucket)

    @Test
    fun `the adapter round-trips the stored bytes`() =
        runTest {
            contract.roundTripsStoredBytes("user-1")
        }

    @Test
    fun `the adapter round-trips an empty payload`() =
        runTest {
            contract.roundTripsEmptyPayload("user-1")
        }

    @Test
    fun `the adapter replaces what was at the path`() =
        runTest {
            contract.uploadReplacesWhatWasThere("user-1")
        }

    @Test
    fun `the adapter lists only what is under the prefix`() =
        runTest {
            contract.listingIsScopedToThePrefix("user-1")
        }

    @Test
    fun `the adapter reports a missing object as a storage failure`() =
        runTest {
            contract.aMissingObjectIsAStorageFailure("user-1")
        }

    @Test
    fun `the adapter refuses an upload that is not the size it was promised`() =
        runTest {
            contract.aWrongSizeIsRefused("user-1")
        }

    @Test
    fun `the adapter reports a missing object as missing`() =
        runTest {
            contract.aFailureIsAStorageFailure(TransportFailure.MISSING) {
                bucket.download("user-1/nope.enc", ByteArraySink())
            }
        }

    @Test
    fun `the adapter reports a refused write as a rejection`() =
        runTest {
            storage.answerWith(HttpStatusCode.InternalServerError)

            contract.aFailureIsAStorageFailure(TransportFailure.REJECTED) {
                bucket.upload("user-1/archive.enc", ByteArraySource(ByteArray(1)), 1)
            }
        }

    @Test
    fun `the adapter reports a lost connection as a network failure`() =
        runTest {
            storage.loseTheConnection()

            contract.aFailureIsAStorageFailure(TransportFailure.NETWORK) {
                bucket.upload("user-1/archive.enc", ByteArraySource(ByteArray(1)), 1)
            }
        }

    @Test
    fun `the adapter propagates a cancellation`() =
        runTest {
            storage.hang()

            val push = async { bucket.upload("user-1/archive.enc", ByteArraySource(ByteArray(1)), 1) }
            // The request has to be on its way before the push is called away: a
            // cancellation that arrives before the call does not say anything about
            // how the adapter treats one.
            storage.awaitRequest()
            push.cancel()

            val failure = runCatching { push.await() }.exceptionOrNull()

            assertTrue(
                failure is kotlinx.coroutines.CancellationException,
                "a cancelled push must arrive as a cancellation, not as a storage failure: $failure",
            )
        }

    /**
     * A provider that hands over the client the fake server answers.
     *
     * The provider is the seam's way of reaching the session, so a test that
     * wants a client of its own has to say so here — the same way
     * [SupabaseClientProvider] is open for every other test in the project.
     */
    private fun provider(client: SupabaseClient): SupabaseClientProvider {
        val secure = MapSecureStorage()
        return object : SupabaseClientProvider(
            resolver = SupabaseConfigResolver(idGenerator = FixedIdGenerator(), buildTime = { null }),
            store = secure,
            sessionStore =
                SecureSessionManager(
                    log = testLogger(),
                    secure = secure,
                    legacy = DataStoreSessionStore(newPreferencesStore(), FixedIdGenerator("dev")),
                ),
        ) {
            override suspend fun client(): SupabaseClient = client
        }
    }
}

/**
 * A Supabase Storage that answers in memory.
 *
 * Three calls, three shapes: an upload replaces the object at the path, a
 * download answers with the stored bytes, and a listing answers with the
 * objects under the prefix it was asked for. The answers the test does not want
 * — a refusal, a lost connection, a request that never comes back — are what
 * the failure cases are made of.
 */
private class FakeSupabaseStorage {
    private val objects = mutableMapOf<String, ByteArray>()

    /** The status every request is answered with, when the test arms one. */
    private var status: HttpStatusCode? = null

    /** Whether the request loses its connection instead of being answered. */
    private var losesConnection = false

    /** Whether the request is never answered at all. */
    private var hangs = false

    /** Completes when a request reaches the storage. */
    private val arrived = CompletableDeferred<Unit>()

    /** Answers every request with [status]. */
    fun answerWith(status: HttpStatusCode) {
        this.status = status
    }

    /** Drops the connection instead of answering. */
    fun loseTheConnection() {
        losesConnection = true
    }

    /** Never answers: the caller has to go away on its own. */
    fun hang() {
        hangs = true
    }

    /** Waits until a request has reached the storage. */
    suspend fun awaitRequest() {
        arrived.await()
    }

    /** The client that talks to this storage. */
    fun client(): SupabaseClient =
        createSupabaseClient("http://localhost:54321", "test-anon-key") {
            httpEngine = MockEngine { request -> answer(request) }
            install(Auth)
            install(Storage)
        }

    private suspend fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        arrived.complete(Unit)
        if (losesConnection) throw IOException("the connection gave up")
        if (hangs) awaitCancellation()
        status?.let { return refusal(it) }

        val path = request.url.encodedPath
        return when {
            path.endsWith(LIST_PATH) -> list(request)
            path.contains(AUTHENTICATED_PATH) -> download(path.substringAfter(AUTHENTICATED_PATH))
            path.contains(OBJECT_PATH) -> upload(request, path.substringAfter(OBJECT_PATH))
            else -> error("the storage was asked for something the backup does not do: $path")
        }
    }

    /**
     * The upload: the object at the path is replaced by what arrived.
     *
     * Replacing is something the request has to ask for, the way the real
     * storage asks for it: an upload that does not carry the upsert header
     * onto an object that is already there is refused. A server that replaced
     * unconditionally would make the "replaces what was there" claim a claim
     * about this server rather than about the adapter, and the adapter could
     * stop asking to replace and the test would stay green.
     */
    private suspend fun MockRequestHandleScope.upload(
        request: HttpRequestData,
        key: String,
    ): HttpResponseData {
        val body = request.body.toByteArray()
        // The size the caller promised is the length the request is lengthened
        // with, and the storage refuses a body of another length. A server that
        // stored it anyway would make the "the size is the one that was
        // promised" claim a claim about this server rather than about the
        // adapter.
        val promised = request.body.contentLength
        if (promised != null && promised != body.size.toLong()) {
            return refusal(HttpStatusCode.BadRequest, "The content length does not match the body")
        }
        if (key in objects && request.headers[UPSERT_HEADER] != "true") {
            return refusal(HttpStatusCode.BadRequest, "The resource already exists")
        }
        objects[key] = body
        return json("""{"Id":"id-$key","Key":"$key"}""")
    }

    /** The download: the stored bytes, or a refusal when there are none. */
    private fun MockRequestHandleScope.download(key: String): HttpResponseData {
        val bytes = objects[key] ?: return refusal(HttpStatusCode.NotFound)
        return respond(bytes, HttpStatusCode.OK)
    }

    /** The listing: the objects under the prefix that was asked for. */
    private suspend fun MockRequestHandleScope.list(request: HttpRequestData): HttpResponseData {
        val prefix =
            request.body
                .toByteArray()
                .decodeToString()
                .substringAfter("\"prefix\":\"")
                .substringBefore("\"")
        val listed =
            objects.keys
                .filter { it.startsWith(prefix) }
                .joinToString(",") { key ->
                    """{"name":"$key","id":"id-$key","updated_at":"2026-01-01T00:00:00Z",""" +
                        """"created_at":"2026-01-01T00:00:00Z","last_accessed_at":"2026-01-01T00:00:00Z",""" +
                        """"metadata":{"size":"${objects.getValue(key).size}"}}"""
                }
        return json("[$listed]")
    }

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData =
        respond(
            body,
            status,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )

    /** A refusal, in the shape the storage answers a failed request in. */
    private fun MockRequestHandleScope.refusal(
        status: HttpStatusCode,
        message: String = status.description,
    ): HttpResponseData =
        json(
            """{"statusCode":${status.value},"error":"$message","message":"$message"}""",
            status,
        )

    private companion object {
        /** Where the storage lists the objects of a bucket. */
        const val LIST_PATH = "/object/list/backups"

        /** Where the storage keeps the objects of a bucket. */
        const val OBJECT_PATH = "/object/backups/"

        /** Where the storage serves an object to a signed-in caller. */
        const val AUTHENTICATED_PATH = "/object/authenticated/backups/"

        /** The header that asks the storage to replace an object that is already there. */
        const val UPSERT_HEADER = "x-upsert"
    }
}
