@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.singularity.todo.feature.ai.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.InitializeResponse
import com.agentclientprotocol.model.LoadSessionRequest
import com.agentclientprotocol.model.ResumeSessionRequest
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionResumeCapabilities
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.TransportFrame
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the pinned protocol library says about resuming a session.
 *
 * ## What this test is for
 *
 * The research note `docs/research/3160-koog-persistence-and-acp-resume.md` records two claims
 * that a dependency bump can invalidate, and this test is what makes them executable:
 *
 * - the pinned client (0.34.0) can issue `session/resume`, in protocol **v1**;
 * - resume is negotiated as a **marker** capability — `sessionCapabilities.resume` is present or
 *   absent, and its presence is the whole answer;
 * - the restart-capable counterpart `session/load` is available on the same client, and
 *   `loadSession` is negotiated as a plain **boolean** on the agent's capabilities.
 *
 * Both are facts about the library, not about the application: the application does not read
 * capabilities yet, and reading them is a later decision. So the test touches the library directly
 * and asserts nothing about the chat.
 */
@Tag("fast")
class AcpResumeCapabilityTest {
    @Test
    fun `the pinned client exposes the resume call`() {
        val resumeCalls =
            Client::class.java.methods.filter { method ->
                method.name.startsWith("resumeSession")
            }

        assertTrue(
            resumeCalls.isNotEmpty(),
            "the pinned client must expose resumeSession — the note's claim depends on it",
        )
    }

    /**
     * The note claims the resume call travels as protocol **v1**, not only as the draft v2.
     *
     * The wire name alone cannot tell the two apart — both `AcpMethod.AgentMethods.V1.SessionResume`
     * and `…V2.SessionResume` are named `session/resume`, and they differ only in their request and
     * response types. The request type is what tells them apart, and the pinned SDK splits it by
     * package: v1 sends `com.agentclientprotocol.model.ResumeSessionRequest`, v2 sends the *same
     * simple name* out of `com.agentclientprotocol.model.v2`.
     *
     * So the test pins three facts that together say "the resume call travels as v1": the v1 method
     * object is bound to the v1 request type, the v2 method object is bound to a *different* type
     * (without that, the first assertion would be vacuous), and the client's own request builder
     * produces the v1 type.
     *
     * ## What the third assertion does and does not say
     *
     * It reads the **private helper that builds the request** — the one that turns a session id and
     * the session parameters into the `ResumeSessionRequest` that `resumeSession()` puts on the
     * wire. It does **not** assert anything about the return type of the public `resumeSession()`,
     * which is a `ClientSession` and is a suspend function, so its Java-level return type is
     * `Object`.
     *
     * A bump that re-points the client at the v2 request type fails on that assertion instead of
     * quietly invalidating the note.
     */
    @Test
    fun `the resume call travels as protocol v1`() {
        assertEquals(
            "session/resume",
            AcpMethod.AgentMethods.V1.SessionResume.methodName.name,
            "the pinned SDK names the v1 resume method session/resume",
        )

        assertEquals(
            "com.agentclientprotocol.model.ResumeSessionRequest",
            AcpMethod.AgentMethods.V1.SessionResume.requestSerializer.descriptor.serialName,
            "the v1 resume method object must be bound to the v1 request type",
        )
        assertEquals(
            "com.agentclientprotocol.model.v2.ResumeSessionRequest",
            AcpMethod.AgentMethods.V2.SessionResume.requestSerializer.descriptor.serialName,
            "the v2 resume method object must stay a different type — " +
                "otherwise the v1 assertion above proves nothing",
        )

        val requestBuilderTypes =
            Client::class.java.declaredMethods
                .filter { it.returnType.simpleName == "ResumeSessionRequest" }
                .map { it.returnType }
                .distinct()

        assertEquals(
            listOf(ResumeSessionRequest::class.java),
            requestBuilderTypes,
            "the client's helper that builds the resume request must build the v1 type " +
                "(com.agentclientprotocol.model.ResumeSessionRequest). This is the request the " +
                "client sends, not the return type of the public resumeSession().",
        )
    }

    /**
     * The client really puts `session/resume` on the wire, with the session id it was given.
     *
     * The two tests above read the *library's* method objects; this one reads the *bytes the client
     * sends*. It runs the pinned client against a fake transport, asks it to resume a session, and
     * looks at the frame that comes out.
     *
     * ## What the wire can and cannot show
     *
     * It shows the method name (`session/resume`) and the payload (the session id and the working
     * directory). It **cannot** show whether the request is the v1 or the v2 one: the two payloads
     * are wire-identical. v1 sends `{sessionId, cwd, mcpServers, additionalDirectories?, _meta?}`
     * and v2 sends `{sessionId, cwd, additionalDirectories, mcpServers, replayFrom?, _meta?}` — but
     * v2's `additionalDirectories` is `@EncodeDefault(NEVER)` and its `replayFrom` is null, and the
     * SDK's JSON omits nulls, so both encode to `{sessionId, cwd, mcpServers}`. That is why the
     * version is pinned by the method object's serializer binding above, and not by the wire.
     */
    @Test
    fun `the client puts session resume on the wire`() =
        runTest {
            val transport = FakeAcpTransport()
            val protocol = Protocol(backgroundScope, transport)
            protocol.start()
            val client = Client(protocol)

            val sessionId = SessionId("session-3160")
            val parameters = SessionCreationParameters(cwd = "/work/project", mcpServers = emptyList())

            val resume =
                launch {
                    client.resumeSession(
                        sessionId,
                        parameters,
                        // The agent never answers in this test, so the factory is never called.
                        ClientOperationsFactory { _, _ -> error("the agent never answers here") },
                    )
                }

            val request = transport.awaitRequest()
            resume.cancel()

            assertEquals(
                "session/resume",
                request.method.name,
                "the client must put the resume method on the wire",
            )

            val params = assertNotNull(request.params, "the resume request must carry params")
            val decoded = ACPJson.decodeFromJsonElement(ResumeSessionRequest.serializer(), params)
            assertEquals(sessionId, decoded.sessionId, "the session id must travel on the wire")
            assertEquals("/work/project", decoded.cwd, "the working directory must travel on the wire")
        }

    /**
     * The restart-capable operation is on the wire too.
     *
     * The note's conclusion is that `session/resume` is the wrong mechanism and `session/load` is
     * the one that survives an agent restart. That makes `session/load` the operation the note
     * actually leans on — so the wire proof has to cover it as well, or an SDK bump could change
     * or remove it while the note keeps claiming it is available.
     *
     * The two calls differ in what they promise, not in how they are sent: `session/load` carries
     * the same session id and working directory as `session/resume`, and the difference lives in
     * the agent's behaviour (replay the retained history as `session/update`s). So the frame is
     * asserted the same way — method name, session id, working directory.
     */
    @Test
    fun `the client puts session load on the wire`() =
        runTest {
            val transport = FakeAcpTransport()
            val protocol = Protocol(backgroundScope, transport)
            protocol.start()
            val client = Client(protocol)

            val sessionId = SessionId("session-3160")
            val parameters = SessionCreationParameters(cwd = "/work/project", mcpServers = emptyList())

            val load =
                launch {
                    client.loadSession(
                        sessionId,
                        parameters,
                        // The agent never answers in this test, so the factory is never called.
                        ClientOperationsFactory { _, _ -> error("the agent never answers here") },
                    )
                }

            val request = transport.awaitRequest()
            load.cancel()

            assertEquals(
                "session/load",
                request.method.name,
                "the client must put the load method on the wire — it is the restart-capable path",
            )

            val params = assertNotNull(request.params, "the load request must carry params")
            val decoded = ACPJson.decodeFromJsonElement(LoadSessionRequest.serializer(), params)
            assertEquals(sessionId, decoded.sessionId, "the session id must travel on the wire")
            assertEquals("/work/project", decoded.cwd, "the working directory must travel on the wire")
        }

    @Test
    fun `resume is negotiated as a marker capability`() {
        val capabilities =
            AgentCapabilities(
                sessionCapabilities =
                    SessionCapabilities(
                        resume = SessionResumeCapabilities(),
                    ),
            )

        assertNotNull(
            capabilities.sessionCapabilities.resume,
            "an agent that advertises resume is read through sessionCapabilities.resume",
        )
        assertNull(
            SessionCapabilities().resume,
            "an agent that advertises nothing is not read as supporting resume",
        )
    }

    @Test
    fun `the marker travels as an empty object`() {
        val json = Json.encodeToString(SessionCapabilities(resume = SessionResumeCapabilities()))

        assertEquals(
            """{"resume":{}}""",
            json,
            "the wire shape is a marker: presence means supported, the payload means nothing",
        )
    }

    /**
     * The capabilities are read off a real `initialize` response, not only built by hand.
     *
     * The two tests above *construct* `AgentCapabilities` and `SessionCapabilities` in Kotlin and
     * look at the values. That proves the model reads a marker correctly, but it never touches the
     * inbound path: the wire JSON an agent actually sends is a different thing from the Kotlin
     * objects the SDK hands you. If the decoding of an `initialize` response changed shape — a
     * capability moved, a boolean became a marker — those tests would keep passing while the
     * negotiation the note describes could no longer happen.
     *
     * So the test decodes representative v1 `initialize` responses: one that advertises both
     * capabilities and one that advertises neither, and asserts what the application would read
     * off them.
     */
    @Test
    fun `the inbound initialize response decodes the resume and load capabilities`() {
        val advertising =
            Json.parseToJsonElement(
                """
                {
                  "protocolVersion": 1,
                  "agentCapabilities": {
                    "loadSession": true,
                    "sessionCapabilities": { "resume": {} }
                  },
                  "agentInfo": { "name": "claude-agent-acp", "version": "0.112.0" }
                }
                """.trimIndent(),
            )
        val silent =
            Json.parseToJsonElement(
                """
                {
                  "protocolVersion": 1,
                  "agentCapabilities": {},
                  "agentInfo": { "name": "codex-acp", "version": "2.12.0" }
                }
                """.trimIndent(),
            )

        val advertisingResponse =
            ACPJson.decodeFromJsonElement(InitializeResponse.serializer(), advertising)
        assertEquals(
            1,
            advertisingResponse.protocolVersion,
            "the pinned SDK decodes the v1 protocol version",
        )
        assertEquals(
            "claude-agent-acp",
            advertisingResponse.agentInfo?.name,
            "the agent identifies itself in the initialize response",
        )
        assertTrue(
            advertisingResponse.agentCapabilities.loadSession,
            "loadSession is read as a boolean off the initialize response",
        )
        assertNotNull(
            advertisingResponse.agentCapabilities.sessionCapabilities.resume,
            "an agent that advertises resume is read through sessionCapabilities.resume",
        )

        val silentResponse = ACPJson.decodeFromJsonElement(InitializeResponse.serializer(), silent)
        assertFalse(
            silentResponse.agentCapabilities.loadSession,
            "an agent that advertises nothing does not support loadSession",
        )
        assertNull(
            silentResponse.agentCapabilities.sessionCapabilities.resume,
            "an agent that advertises nothing does not support resume",
        )
    }

    /**
     * A transport that records what the client sends and answers nothing.
     *
     * It is a fake, not a mock: it has no expectations to set, only a place to put the frames.
     */
    private class FakeAcpTransport : BaseTransport() {
        private val sent = Channel<TransportFrame>(Channel.UNLIMITED)

        override fun start() {
            _state.value = Transport.State.STARTED
        }

        override fun send(frame: TransportFrame) {
            sent.trySend(frame)
        }

        override fun close() {
            _state.value = Transport.State.CLOSED
        }

        /** The next request the client sends, or nothing if it sends nothing. */
        suspend fun awaitRequest(): JsonRpcRequest {
            val frame = sent.receive()
            val message = (frame as TransportFrame.Single).message
            return message as JsonRpcRequest
        }
    }
}
