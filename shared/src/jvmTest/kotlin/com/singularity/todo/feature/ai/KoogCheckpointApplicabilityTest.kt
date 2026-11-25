package com.singularity.todo.feature.ai

import com.singularity.todo.arch.SourceScan
import com.singularity.todo.core.ai.ToolCallLoop
import com.singularity.todo.core.llm.TextGenPort
import com.singularity.todo.feature.ai.chat.ChatTurn
import org.junit.jupiter.api.Tag
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
 * - the checkpoint feature is **not a functional feature**: it installs into an
 *   `AIAgentGraphPipeline` and into an `AIAgentPlannerPipeline` (both graph-shaped and
 *   planner-shaped agents), is not an `AIAgentFunctionalFeature`, and casts the running strategy
 *   to `AIAgentGraphStrategy` (or to the planner of an `AIAgentPlannerStrategy`);
 * - the chat holds no Koog agent at all — neither in the loop, nor in the port it turns through,
 *   nor in the implementation that actually runs the chat;
 * - the memory feature ships exactly one history provider, and it is in-memory.
 *
 * The applicability conclusion rests on the contract assertions and on the production path. The
 * API-shape assertion (`runFromCheckpoint` takes an `AIAgent`) is a *compatibility* tripwire: it
 * says the dependency is still present and still shaped the same way, not that the feature is
 * applicable to this chat.
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
            "the checkpoint API restores a Koog agent — that is what restricts it to graph and planner agents",
        )
    }

    /**
     * The checkpoint feature is not installable into a functional agent — that is what makes it
     * inapplicable to this chat.
     *
     * The test above pins the *shape* of the public API: `runFromCheckpoint` takes an `AIAgent`.
     * That shape alone does not carry the conclusion. An `AIAgent` could, in a later Koog version,
     * be restored into a functional agent as well, and the note would go stale while this test
     * still passed.
     *
     * The decisive fact is the feature's own contract. `Persistence.Feature` implements *two*
     * feature interfaces: `AIAgentGraphFeature` and `AIAgentPlannerFeature`. It therefore installs
     * into an `AIAgentGraphPipeline` — casting the strategy to `AIAgentGraphStrategy` — and into an
     * `AIAgentPlannerPipeline` — casting `AIAgentPlannerStrategy.planner`. It is *not* an
     * `AIAgentFunctionalFeature`, and a functional agent (`FunctionalAIAgent`) has a different
     * pipeline and a different strategy, so the checkpoint feature cannot be installed there at
     * all. The built-in chat is a functional-shaped hand-written loop, which is why it is outside
     * the feature's reach.
     *
     * So the tripwire pins the contract itself: the feature is a graph feature *and* a planner
     * feature, it is *not* a functional feature, its two installation paths take a graph pipeline
     * and a planner pipeline, and a functional agent's strategy is neither.
     */
    @Test
    fun `the checkpoint feature installs into graph and planner pipelines but not a functional one`() {
        val feature = Class.forName("ai.koog.agents.snapshot.feature.Persistence\$Feature")
        val graphFeature = Class.forName("ai.koog.agents.core.feature.AIAgentGraphFeature")
        val plannerFeature = Class.forName("ai.koog.agents.core.feature.AIAgentPlannerFeature")
        val functionalFeature = Class.forName("ai.koog.agents.core.feature.AIAgentFunctionalFeature")
        val graphPipeline = Class.forName("ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline")
        val plannerPipeline = Class.forName("ai.koog.agents.core.feature.pipeline.AIAgentPlannerPipeline")
        val graphStrategy = Class.forName("ai.koog.agents.core.agent.entity.AIAgentGraphStrategy")
        val functionalStrategy = Class.forName("ai.koog.agents.core.agent.AIAgentFunctionalStrategy")

        assertTrue(
            graphFeature.isAssignableFrom(feature),
            "the checkpoint feature is a graph feature — that is what restricts it to graph agents",
        )
        assertTrue(
            plannerFeature.isAssignableFrom(feature),
            "the checkpoint feature is also a planner feature — planner-based agents are supported too",
        )
        assertFalse(
            functionalFeature.isAssignableFrom(feature),
            "the checkpoint feature must not be installable into a functional agent's pipeline",
        )
        assertTrue(
            feature.declaredMethods.any { method ->
                method.name == "install" && method.parameterTypes.any { it == graphPipeline }
            },
            "the checkpoint feature installs into a graph pipeline",
        )
        assertTrue(
            feature.declaredMethods.any { method ->
                method.name == "install" && method.parameterTypes.any { it == plannerPipeline }
            },
            "the checkpoint feature installs into a planner pipeline",
        )
        assertFalse(
            graphStrategy.isAssignableFrom(functionalStrategy),
            "a functional agent's strategy is not a graph strategy, so the checkpoint feature " +
                "cannot reach it",
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

    /**
     * The chat's production path, read from the source.
     *
     * The two tests above look at *interfaces* — the loop's fields, the turn's fields, the port's
     * signatures. That is one layer up from the code that actually runs the chat, and a Koog agent
     * could be introduced inside `KoogAgentService.streamChat()` without changing any of them: the
     * port would still be project-owned, the turn would still hold no agent, and the loop would
     * still be agent-free — while the chat had already moved onto a Koog graph, which is exactly
     * the change that would make Koog's checkpointing applicable.
     *
     * So the tripwire sits on the implementation itself: the streaming chat must run the
     * hand-written loop, and must not build or run a Koog agent.
     *
     * The source is read through [SourceScan], the repository's one source-reading helper, because
     * the braces of a Kotlin body have to be matched by a lexer that knows about strings and
     * comments — and because a second copy of that matcher would be a second set of bugs in it.
     */
    @Test
    fun `the streaming chat runs the tool call loop, not a koog agent`() {
        val source = SourceScan.file("feature/ai/KoogAgentService.kt").readText()

        val declaration = source.indexOf("override fun streamChat(")
        assertTrue(
            declaration >= 0,
            "the streaming chat must still live in KoogAgentService — the note's call chain depends on it",
        )
        val closeParen = SourceScan.closingParen(source, source.indexOf('(', declaration))
        assertTrue(closeParen > 0, "the streaming chat's parameter list must close")

        val flowStart = source.indexOf("flow {", closeParen)
        assertTrue(
            flowStart > 0,
            "the streaming chat must still build its answer through a flow { ... } block",
        )
        val body =
            assertNotNull(
                SourceScan.bodyOf(source, flowStart + "flow {".length - 1),
                "the streaming chat's body must be readable",
            )

        assertTrue(
            body.contains("ToolCallLoop"),
            "the chat runs the hand-written loop; moving it onto a Koog graph would change the decision",
        )
        assertTrue(
            body.contains("streamingExecutor.executeStreaming"),
            "the chat answers through the project-owned executor, not through a Koog agent",
        )
        assertFalse(
            body.contains("AIAgent"),
            "the chat must not build a Koog agent — that is what would make the checkpointing applicable",
        )
        assertFalse(
            body.contains("createAgent"),
            "the chat must not go through the Koog agent builder that the non-chat path uses",
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
