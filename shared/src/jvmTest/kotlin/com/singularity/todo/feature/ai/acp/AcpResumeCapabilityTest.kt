@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.singularity.todo.feature.ai.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.model.AcpMethod
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ResumeSessionRequest
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionResumeCapabilities
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
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
 *   absent, and its presence is the whole answer.
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
     * (without that, the first assertion would be vacuous), and the client builds the v1 type.
     * A bump that re-points `resumeSession` at v2 fails on the last assertion instead of quietly
     * invalidating the note.
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

        val resumeRequestTypes =
            Client::class.java.declaredMethods
                .map { it.returnType }
                .filter { it.simpleName == "ResumeSessionRequest" }
                .distinct()

        assertEquals(
            listOf(ResumeSessionRequest::class.java),
            resumeRequestTypes,
            "the client must build the v1 resume request — a bump that re-points resumeSession " +
                "at v2 breaks the note's claim",
        )
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
            capabilities.sessionCapabilities?.resume,
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
}
