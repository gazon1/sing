package com.singularity.todo.core.error

import com.singularity.todo.core.sync.BatchPushRequest
import com.singularity.todo.core.sync.SupabaseSyncApiClient
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncRpc
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The four kinds of failure a call to a server can have.
 *
 * These are the four that used to be one. The test is on the *decision* — a
 * status code, the server's own code, and whether the server answered at all —
 * because that is where the difference between "the session is gone" and "the
 * deployment is missing a migration" is made.
 */
@Tag("fast")
class TransportFailureTest {

    @Test
    fun `a refused identity is the unauthorized class`() {
        assertEquals(
            TransportFailure.UNAUTHORIZED,
            classifyTransportFailure(statusCode = 401, serverCode = null, answered = true),
        )
    }

    @Test
    fun `a function that is not in the schema cache is the missing class`() {
        assertEquals(
            TransportFailure.MISSING,
            classifyTransportFailure(
                statusCode = 404,
                serverCode = "PGRST202",
                answered = true,
            ),
        )
    }

    @Test
    fun `a path that is not there is the missing class too`() {
        assertEquals(
            TransportFailure.MISSING,
            classifyTransportFailure(statusCode = 404, serverCode = null, answered = true),
        )
    }

    @Test
    fun `a policy that refused a write is the rejected class`() {
        // The 403 that a row-level policy answers with. It is the case that
        // cost the debugging session: an anon key and a refused policy look the
        // same in a log, and they are not the same thing.
        assertEquals(
            TransportFailure.REJECTED,
            classifyTransportFailure(statusCode = 403, serverCode = null, answered = true),
        )
    }

    @Test
    fun `a server that failed is the rejected class`() {
        assertEquals(
            TransportFailure.REJECTED,
            classifyTransportFailure(statusCode = 500, serverCode = null, answered = true),
        )
    }

    @Test
    fun `a call that never arrived is the network class`() {
        assertEquals(
            TransportFailure.NETWORK,
            classifyTransportFailure(statusCode = null, serverCode = null, answered = false),
        )
    }

    @Test
    fun `an answer that did not parse is the rejected class, not the network one`() {
        assertEquals(
            TransportFailure.REJECTED,
            classifyTransportFailure(statusCode = null, serverCode = null, answered = true),
        )
    }

    @Test
    fun `the code names the class, under the area's own prefix`() {
        val error = transportError(
            area = "sync",
            failure = TransportFailure.UNAUTHORIZED,
            message = "the session is gone",
        )
        assertEquals("sync.unauthorized", error.code)
        assertTrue(error is AppError.Unauthorized)
    }

    @Test
    fun `the class that means something else keeps the name the dashboard knows`() {
        val error = transportError(
            area = "sync",
            failure = TransportFailure.REJECTED,
            message = "the server said no",
            rejectedCode = "sync.rpc_failed",
        )
        assertEquals("sync.rpc_failed", error.code)
        assertTrue(error is AppError.Rejected)
    }

    @Test
    fun `the message says what happened and what the server said`() {
        val error = transportError(
            area = "backup",
            failure = TransportFailure.NETWORK,
            message = "the archive could not be uploaded: connection refused",
        )
        assertEquals("backup.network_failed", error.code)
        assertTrue(
            error.message?.contains("could not be reached") == true,
            "the message does not say what happened: ${error.message}",
        )
        assertTrue(
            error.message?.contains("connection refused") == true,
            "the message does not carry the server's own words: ${error.message}",
        )
    }

    /**
     * The classification has to survive the trip to the code that reports it.
     *
     * A fake RPC throws the classified failure, and the client has to turn it
     * into the error the crash reporter groups by. The code is the whole point:
     * one group for four defects is how "the anon key went instead of the
     * user's token" and "the policy refused the write" ended up together.
     */
    @Test
    fun `a classified failure reaches the caller with its own code`() = runTest {
        val error = assertFailsWith<AppError.Unauthorized> {
            clientFailingWith(TransportFailure.UNAUTHORIZED).batchPush(emptyRequest())
        }
        assertEquals("sync.unauthorized", error.code)
    }

    @Test
    fun `a missing function reaches the caller with its own code`() = runTest {
        val error = assertFailsWith<AppError.NotFound> {
            clientFailingWith(TransportFailure.MISSING).batchPush(emptyRequest())
        }
        assertEquals("sync.not_found", error.code)
    }

    @Test
    fun `a lost connection reaches the caller with its own code`() = runTest {
        val error = assertFailsWith<AppError.Network> {
            clientFailingWith(TransportFailure.NETWORK).batchPush(emptyRequest())
        }
        assertEquals("sync.network_failed", error.code)
    }

    @Test
    fun `a failure nobody classified keeps the code the dashboard knows`() = runTest {
        val rpc = object : SyncRpc {
            override suspend fun call(function: String, params: JsonObject): String =
                throw IllegalStateException("something nobody named")
        }
        val error = assertFailsWith<AppError.Network> {
            SupabaseSyncApiClient(rpc).batchPush(emptyRequest())
        }
        assertEquals("sync.rpc_failed", error.code)
    }

    private fun clientFailingWith(failure: TransportFailure): SyncApiClient {
        val rpc = object : SyncRpc {
            override suspend fun call(function: String, params: JsonObject): String =
                throw TransportException(failure, "$function failed: the server said no")
        }
        return SupabaseSyncApiClient(rpc)
    }

    private fun emptyRequest() = BatchPushRequest(
        deviceId = "d",
        profileId = "work",
        patches = emptyList(),
    )
}
