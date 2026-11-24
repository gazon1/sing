@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.singularity.todo.feature.ai.acp

import com.agentclientprotocol.client.Client
import com.agentclientprotocol.model.AgentCapabilities
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
