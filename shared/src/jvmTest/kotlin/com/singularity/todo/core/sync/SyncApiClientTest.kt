package com.singularity.todo.core.sync

import com.singularity.todo.core.error.AppError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire contract, tested without a network.
 *
 * ## Why a fake that returns raw JSON
 *
 * Everything worth checking here is a property of the bytes, not of the HTTP
 * client: which field a 64-bit number is read from, what an absent `ok` means,
 * what happens to a type this build has never heard of. Returning canned JSON
 * from a [SyncRpc] puts those decisions directly under test, where a real server
 * would put them behind a deploy and a round trip.
 *
 * The tests are also the only place the *request* is inspected, since a real
 * server would not tell a client it built the wrong body.
 */
@Tag("fast")
class SyncApiClientTest {

    /** Records what was sent, and replies with whatever the test scripted. */
    private class RecordingRpc(private val respond: (String, JsonObject) -> String) : SyncRpc {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun call(function: String, params: JsonObject): String {
            calls += function to params
            return respond(function, params)
        }
    }

    private fun clientReturning(body: String): Pair<SupabaseSyncApiClient, RecordingRpc> {
        val rpc = RecordingRpc { _, _ -> body }
        return SupabaseSyncApiClient(rpc) to rpc
    }

    // ── Sequence numbers ────────────────────────────────────────────────────

    @Test
    fun `a log position past the double range is read exactly`() = runTest {
        // 2^53 + 1. Every Long reads this correctly; a Double cannot represent it
        // and rounds it to 2^53, which is a log position the server never issued.
        // The client would then resume from the wrong place and skip an event.
        val exact = 9_007_199_254_740_993L
        val (client, _) = clientReturning(
            """[{"serverLsn":$exact,"entityId":"e1","entityType":"task",
                "eventType":"created","data":{"title":"t"},"serverTs":1700000000000}]""",
        )

        val events = client.getEventsSince(0)

        assertEquals(exact, events.single().serverLsn)
    }

    @Test
    fun `a large log position survives a round trip back to the server`() = runTest {
        val exact = 9_007_199_254_740_993L
        val (client, rpc) = clientReturning("[]")

        client.getEventsSince(exact - 2)

        val since = rpc.calls.single().second.getValue("p_since_lsn") as JsonPrimitive
        assertEquals(exact - 2, since.content.toLong())
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    @Test
    fun `an event is read with the profile it belongs to`() = runTest {
        val (client, _) = clientReturning(
            """[{"serverLsn":7,"entityId":"e1","entityType":"note","eventType":"updated",
                "data":{"title":"n"},"serverTs":1700000000000,"profileId":"work"}]""",
        )

        val event = client.getEventsSince(0).single()

        assertEquals(DocType.Note, event.entityType)
        assertEquals(SyncEventType.UPDATED, event.eventType)
        assertEquals("work", event.profileId)
        assertEquals(7L, event.serverLsn)
        assertEquals(1_700_000_000_000L, event.createdAt)
    }

    @Test
    fun `an event from a server without profiles still parses`() = runTest {
        // The older server has no profile dimension. Defaulting to empty keeps it
        // usable, and empty means "applies to whichever scope pulls it" — see
        // SyncEvent.belongsTo.
        val (client, _) = clientReturning(
            """[{"serverLsn":1,"entityId":"e1","entityType":"task","eventType":"created","data":{}}]""",
        )

        assertEquals("", client.getEventsSince(0).single().profileId)
    }

    @Test
    fun `every field operation reaches the server under its wire name`() = runTest {
        val rpc = RecordingRpc { _, _ -> """{"results":[]}""" }
        val client = SupabaseSyncApiClient(rpc)

        client.batchPush(
            BatchPushRequest(
                deviceId = "device-1",
                profileId = "work",
                patches = listOf(
                    deltaPatch {
                        patchId = "p1"
                        hlc = Hlc.of(1700, 0, "node-1")
                        set("title", "hello")
                        unset("description")
                    },
                ),
            ),
        )

        val patch = rpc.calls.single().second.getValue("p_patches").let { it as JsonArray }.single()
        val obj = patch as JsonObject
        assertEquals("work", obj.getValue("profileId").jsonPrimitive.content)
        assertEquals("task", obj.getValue("entityType").jsonPrimitive.content)

        val ops = obj.getValue("ops") as JsonArray
        val set = ops[0] as JsonObject
        assertEquals("set", set.getValue("op").jsonPrimitive.content)
        assertEquals("hello", set.getValue("value").jsonPrimitive.content)

        val unset = ops[1] as JsonObject
        assertEquals("unset", unset.getValue("op").jsonPrimitive.content)
        assertTrue(
            "unset" !in unset,
            "an unset names a field to clear; sending a value would claim the field's value is null",
        )
    }

    @Test
    fun `the clock is sent as the object the server compares`() = runTest {
        val rpc = RecordingRpc { _, _ -> """{"results":[]}""" }
        val client = SupabaseSyncApiClient(rpc)

        client.batchPush(
            BatchPushRequest(
                deviceId = "d",
                profileId = "work",
                patches = listOf(
                    deltaPatch {
                        patchId = "p1"
                        hlc = Hlc.of(1700, 3, "node-1")
                    },
                ),
            ),
        )

        val patch = (rpc.calls.single().second.getValue("p_patches") as JsonArray).single()
        val clock = (patch as JsonObject).getValue("hlc") as JsonObject
        assertEquals("1700", clock.getValue("p").jsonPrimitive.content)
        assertEquals("3", clock.getValue("c").jsonPrimitive.content)
        assertEquals("node-1", clock.getValue("n").jsonPrimitive.content)
    }

    @Test
    fun `a push result is read, including the server's own refusals`() = runTest {
        val (client, _) = clientReturning(
            """{"results":[
                {"patchId":"p1","ok":true,"cached":false,"conflict":false,"newVersion":4},
                {"patchId":"p2","ok":false,"cached":false,"conflict":false,
                 "error":"field_not_writable","lost":false,"legacy":false}
            ]}""",
        )

        val results = client.batchPush(
            BatchPushRequest(
                deviceId = "d",
                profileId = "work",
                patches = listOf(
                    deltaPatch {
                        patchId = "p1"
                        hlc = Hlc.of(1, 0, "n")
                    },
                ),
            ),
        ).results

        assertEquals(2, results.size)
        assertTrue(results[0].ok)
        assertEquals(4L, results[0].newVersion)
        assertTrue(!results[1].ok)
        assertEquals("field_not_writable", results[1].error)
    }

    // ── Failures are errors, not exceptions ─────────────────────────────────

    @Test
    fun `a transport failure becomes an AppError rather than escaping`() = runTest {
        val rpc = object : SyncRpc {
            override suspend fun call(function: String, params: JsonObject): String =
                throw IllegalStateException("HTTP 401 from $function")
        }

        val error = assertFailsWith<AppError.Network> {
            SupabaseSyncApiClient(rpc).batchPush(
                BatchPushRequest(
                    deviceId = "d",
                    profileId = "work",
                    patches = listOf(
                        deltaPatch {
                            patchId = "p1"
                            hlc = Hlc.of(1, 0, "n")
                        },
                    ),
                ),
            )
        }

        assertEquals("sync.rpc_failed", error.code)
        // The vendor's own exception would say "HTTP 401" and nothing about what
        // the app should do; a crash dashboard groups by code, not by prose.
        assertTrue(error.cause is IllegalStateException, "the original failure must survive")
    }

    @Test
    fun `a response that is not JSON is an error, not a crash`() = runTest {
        // A proxy's HTML error page is the realistic source. Reading it as a
        // response would throw a parse exception from the middle of the pull.
        val (client, _) = clientReturning("<html>502 Bad Gateway</html>")

        val error = assertFailsWith<AppError.Network> { client.getEventsSince(0) }
        assertEquals("sync.malformed_response", error.code)
    }

    @Test
    fun `a result with no ok is not treated as applied`() = runTest {
        // The dangerous default. `ok` absent read as `true` would let the engine
        // delete the patch from the outbox and advance the shadow past a write the
        // server never made, and the next diff would be computed against it.
        val (client, _) = clientReturning("""{"results":[{"patchId":"p1"}]}""")

        val result = client.batchPush(
            BatchPushRequest(
                deviceId = "d",
                profileId = "work",
                patches = listOf(
                    deltaPatch {
                        patchId = "p1"
                        hlc = Hlc.of(1, 0, "n")
                    },
                ),
            ),
        ).results.single()

        assertTrue(!result.ok)
    }

    @Test
    fun `an unknown document type is refused rather than skipped`() = runTest {
        // Skipping would drop the event and move the cursor past it, and the data
        // would be gone with only a pull summary to show for it.
        val (client, _) = clientReturning(
            """[{"serverLsn":1,"entityId":"e1","entityType":"habit","eventType":"created","data":{}}]""",
        )

        val error = assertFailsWith<AppError.Network> { client.getEventsSince(0) }
        assertEquals("sync.unknown_entity_type", error.code)
    }

    @Test
    fun `a patch with no clock is refused before it is sent`() = runTest {
        // Omitting the key is not neutral: the server reads an absent clock as a
        // legacy full-snapshot client, expands an absent document into zero
        // operations and refuses the patch — correctly, and uselessly, because the
        // local cause was a builder that forgot a clock.
        val rpc = RecordingRpc { _, _ -> """{"results":[]}""" }
        val client = SupabaseSyncApiClient(rpc)

        val error = assertFailsWith<AppError.Validation> {
            client.batchPush(
                BatchPushRequest(
                    deviceId = "d",
                    profileId = "work",
                    patches = listOf(deltaPatch { patchId = "p1" }),
                ),
            )
        }

        assertEquals("sync.patch_without_clock", error.code)
        assertTrue(rpc.calls.isEmpty(), "a patch the server cannot merge must not be sent")
    }

    @Test
    fun `the health check answers with a result rather than throwing`() = runTest {
        val ok = SupabaseSyncApiClient(RecordingRpc { _, _ -> """{"ok":true}""" })
        assertTrue(ok.testConnection().isSuccess)

        val unreachable = SupabaseSyncApiClient(
            object : SyncRpc {
                override suspend fun call(function: String, params: JsonObject): String =
                    throw IllegalStateException("no route to host")
            },
        )
        val failure = unreachable.testConnection().exceptionOrNull()
        assertTrue(failure is AppError, "a health check answers a question; it does not raise")
    }

    @Test
    fun `no remote configuration is served by the sync schema`() = runTest {
        val (client, _) = clientReturning("{}")
        assertNull(client.getRemoteConfig())
    }

    @Test
    fun `a malformed event names what it was parsing`() = runTest {
        val (client, _) = clientReturning("""[{"entityId":"e1","entityType":"task","eventType":"created"}]""")

        val error = assertFailsWith<AppError.Network> { client.getEventsSince(0) }
        assertEquals("sync.malformed_response", error.code)
        assertTrue("serverLsn" in error.message.orEmpty(), "the message should name the missing field")
    }

    @Test
    fun `an empty feed is an empty page, not a failure`() = runTest {
        val (client, rpc) = clientReturning("[]")

        assertEquals(emptyList(), client.getEventsSince(42))
        assertEquals("sync_events_since", rpc.calls.single().first)
    }

    @Test
    fun `the pull request carries the position and the page size`() = runTest {
        val (client, rpc) = clientReturning("[]")

        client.getEventsSince(99, limit = 25)

        val params = rpc.calls.single().second
        assertEquals(99, (params.getValue("p_since_lsn") as JsonPrimitive).content.toLong())
        assertEquals(25, (params.getValue("p_limit") as JsonPrimitive).content.toInt())
    }

    @Test
    fun `a health response is not parsed as a feed`() = runTest {
        val (client, rpc) = clientReturning("""{"status":"ok"}""")

        client.testConnection()

        assertEquals("sync_health", rpc.calls.single().first)
    }

    @Test
    fun `a patch is sent with exactly the keys the batch function reads`() = runTest {
        // The request is hand-built, so a field added to the wrong builder is
        // dropped silently. The server rejects a patch missing any of these three,
        // and reads the rest; the assertion is the list, not the count.
        val rpc = RecordingRpc { _, _ -> """{"results":[]}""" }
        SupabaseSyncApiClient(rpc).batchPush(
            BatchPushRequest(
                deviceId = "device-9",
                profileId = "work",
                patches = listOf(
                    deltaPatch {
                        patchId = "p1"
                        hlc = Hlc.of(1, 0, "n")
                    },
                ),
            ),
        )

        val body = rpc.calls.single().second
        assertEquals(setOf("p_patches"), body.keys)

        val patch = (body.getValue("p_patches") as JsonArray).single() as JsonObject
        assertEquals(
            setOf("patchId", "entityId", "entityType", "profileId", "protocolVersion", "isDelete", "ops", "hlc"),
            patch.keys,
        )
    }
}
