package com.singularity.todo.feature.ai

import com.singularity.todo.core.ai.ToolCallLoop
import com.singularity.todo.core.llm.TextGenPort
import com.singularity.todo.feature.ai.chat.ChatTurn
import org.junit.jupiter.api.Tag
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether Koog's checkpointing can serve the built-in chat.
 *
 * ## What this test is for
 *
 * The research note `docs/research/3160-koog-persistence-and-acp-resume.md` concludes that Koog's
 * checkpointing **does not apply** to the built-in chat: the chat is a hand-written loop
 * (`core/ai/ToolCallLoop.kt`), not a Koog agent graph.
 *
 * The tempting wrong reason for that conclusion is "the feature is not on the classpath". It *is*:
 * `ai.koog:koog-agents` pulls `agents-features-snapshot` and `agents-features-memory` in as
 * runtime dependencies. The real reasons are structural, and this test is what keeps them
 * executable:
 *
 * - the checkpoint API is defined in terms of a Koog `AIAgent`, so it can only restore a graph;
 * - the chat holds no Koog agent at all — neither in the loop, nor in the port it turns through;
 * - the memory feature ships exactly one history provider, and it is in-memory.
 *
 * If somebody rewires the chat onto a Koog agent graph, or adds a durable provider to the memory
 * feature, this test fails and the decision is re-opened instead of being quietly inherited.
 */
@Tag("fast")
class KoogCheckpointApplicabilityTest {
    private val koogAgentPackage = "ai.koog.agents.core.agent"

    @Test
    fun `the checkpoint feature is on the classpath and restores a koog agent`() {
        val feature = Class.forName("ai.koog.agents.snapshot.feature.Persistence\$Feature")

        assertTrue(
            feature.declaredMethods.any { method ->
                method.name.contains("runFromCheckpoint") &&
                    method.parameterTypes.any { it.name == "$koogAgentPackage.AIAgent" }
            },
            "the checkpoint API restores a Koog agent — that is what makes it graph-only",
        )
    }

    @Test
    fun `the chat loop holds no koog agent`() {
        val agentTypes =
            ToolCallLoop::class.java.declaredFields
                .map { it.type.name }
                .filter { it.startsWith(koogAgentPackage) }

        assertEquals(
            emptyList<String>(),
            agentTypes,
            "the built-in chat is a hand-written loop; wiring it to a Koog agent would change the decision",
        )
    }

    /**
     * The loop is one layer down, so the boundary above it is pinned too.
     *
     * The chat does not talk to `ToolCallLoop` directly: it talks to a project-owned port
     * (`TextGenPort`) through a turn (`ChatTurn`). That port is exactly where a Koog agent would
     * have to be plugged in — a rewire would either change the port's signatures or change what
     * the turn holds — so the tripwire sits on both. Without this, the loop check alone would
     * keep passing after the chat had already been moved onto a Koog agent.
     */
    @Test
    fun `the chat turns through a project-owned port`() {
        val koogTypes =
            (
                ChatTurn::class.java.declaredFields.map { it.type.name } +
                    TextGenPort::class.java.methods.flatMap { method ->
                        method.parameterTypes.map { it.name } + method.returnType.name
                    }
            ).filter { it.startsWith(koogAgentPackage) }

        assertEquals(
            emptyList<String>(),
            koogTypes,
            "the chat turns through a project-owned port; plugging a Koog agent into it would change the decision",
        )
    }

    @Test
    fun `the memory feature ships only an in-memory provider`() {
        val provider = Class.forName("ai.koog.agents.chatMemory.feature.ChatHistoryProvider")
        val jar =
            File(
                provider.protectionDomain.codeSource.location
                    .toURI(),
            )
        val classNames =
            JarFile(jar).use { archive ->
                archive
                    .entries()
                    .asSequence()
                    .map { entry -> entry.name }
                    .toList()
            }
        val providers =
            classNames
                .filter { it.startsWith("ai/koog/agents/chatMemory/") && it.endsWith(".class") }
                .mapNotNull { path ->
                    runCatching {
                        Class.forName(
                            path.removeSuffix(".class").replace('/', '.'),
                            false,
                            provider.classLoader,
                        )
                    }.getOrNull()
                }.filter { it != provider && provider.isAssignableFrom(it) }
                .map { it.simpleName }
                .toList()

        assertEquals(
            listOf("InMemoryChatHistoryProvider"),
            providers,
            "a durable provider would change this decision",
        )
    }
}
