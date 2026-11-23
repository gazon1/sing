@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.Tool
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.TypeToken
import ai.koog.utils.time.KoogClock
import com.singularity.todo.core.ai.ToolCallLoop
import com.singularity.todo.core.ai.filterTools
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.ToolCallStatus
import com.singularity.todo.feature.ai.tools.UpdateTaskTool
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The arguments a stand-in tool accepts. */
@Serializable
private data class StubArgs(val value: String = "")

/** The catalogue domain every scope carries, because its tools act on other domains' data. */
private const val CROSS_CUTTING_DOMAIN = "ai"

/**
 * A stand-in for a catalogue tool.
 *
 * The real tools need repositories; the questions this evaluation asks are about the
 * *surface*, not about what a tool does with its arguments. So a stand-in answers, and
 * the loop's events tell "the call was honoured" apart from "the call was refused".
 */
private class StubTool(private val toolName: String) :
    SimpleTool<StubArgs>(TypeToken.of(StubArgs::class.java), toolName, "A stand-in for the $toolName tool.") {
    override suspend fun execute(args: StubArgs): String = """{"tool":"$toolName"}"""
}

/**
 * The model, scripted round by round.
 *
 * The loop asks the model again after every round in which the model called a tool, so
 * a script has one entry per round.
 */
private class ScriptedModel(private val rounds: List<List<StreamFrame>>) {
    private var round = 0

    fun next(): Flow<StreamFrame> {
        val frames = rounds.getOrElse(round) { listOf(StreamFrame.TextDelta("done")) }
        round++
        return flowOf(*frames.toTypedArray())
    }
}

/**
 * One evaluation scenario.
 *
 * @param domains the catalogue domains the scoped candidate is built from; the
 *   cross-cutting `ai` domain is added to every scope.
 * @param expectedTool the tool the scenario is about.
 * @param confusableTool a tool from another domain that the model reaches for by
 *   mistake — the call that the full toolbox honours and a scoped toolbox refuses.
 * @param expectedScopedSize how many tools the scoped condition offers — the number the
 *   decision note publishes, so the two cannot drift apart unnoticed.
 */
private data class Scenario(
    val title: String,
    val prompt: String,
    val domains: Set<String>,
    val expectedTool: String,
    val expectedArgs: String,
    val confusableTool: String,
    val expectedScopedSize: Int,
)

/** What one turn did. */
private class Turn(val offered: List<String>, val calls: Int, val executed: List<String>, val refused: List<String>)

/**
 * The evaluation behind issue #3098: does a scoped toolbox beat the full one?
 *
 * ## What is compared
 *
 * The built-in agent is offered one toolbox. A scoped candidate is offered the tools of
 * one domain (plus the cross-cutting `ai` domain), taken from [McpToolCatalog] — the
 * only grouping the repository owns today. Both conditions run the *same* scenarios,
 * the *same* scripted model and the *same* turn.
 *
 * ## What the scripted model is, and is not
 *
 * The model is scripted: it calls the tool the scenario expects, then calls one
 * plausible wrong tool, then answers in words. It is the **same script in both
 * conditions** — that is the point. A scripted model cannot measure how *well* a real
 * model chooses, and this test does not claim to: selection accuracy needs a live model
 * and is recorded as a manual experiment in the decision note.
 *
 * What the scripted model *does* measure is everything that depends on the toolbox
 * rather than on the model: which tools are reachable, which calls are honoured, which
 * are refused, how much surface is exposed, and whether the write/proposal boundary
 * survives.
 */

@Tag("fast")
class ScopedToolsetEvaluationTest {
    private val scenarios =
        listOf(
            Scenario(
                title = "task lookup",
                prompt = "What are my overdue tasks?",
                domains = setOf("tasks"),
                expectedTool = "list_tasks",
                expectedArgs = "{}",
                confusableTool = "list_projects",
                expectedScopedSize = 25,
            ),
            Scenario(
                title = "note read",
                prompt = "Read my note about the launch plan.",
                domains = setOf("notes"),
                expectedTool = "get_note",
                expectedArgs = """{"noteId":"n-1"}""",
                confusableTool = "get_task",
                expectedScopedSize = 21,
            ),
            Scenario(
                title = "project listing",
                prompt = "Which projects do I have?",
                domains = setOf("projects"),
                expectedTool = "list_projects",
                expectedArgs = "{}",
                confusableTool = "list_tasks",
                expectedScopedSize = 20,
            ),
            Scenario(
                title = "planning",
                prompt = "Break the task \"Plan the launch\" into subtasks.",
                domains = setOf("tasks", "ai"),
                expectedTool = "decompose_task",
                expectedArgs = """{"taskId":"t-1"}""",
                confusableTool = "delete_note",
                expectedScopedSize = 25,
            ),
        )

    /**
     * The toolbox of one condition.
     *
     * `scope == null` is the full toolbox. A scope always carries the cross-cutting `ai`
     * domain as well: the catalogue groups tools by *kind of tool*, so the `ai` tools are
     * the ones that act on another domain's data (`summarize_note`, `rewrite_note` …) —
     * a scope without them is not the scope the research describes.
     *
     * The scope and the kill switch compose into an intersection, so a scope can never
     * bring a disabled tool back.
     */
    private fun toolbox(
        scope: Set<String>?,
        flags: Map<String, Boolean> = emptyMap(),
        real: Map<String, Tool<*, *>> = emptyMap(),
    ): List<Tool<*, *>> {
        val effective = scope?.plus(CROSS_CUTTING_DOMAIN)
        val entries = McpToolCatalog.catalog.filter { effective == null || it.domain in effective }
        val tools = entries.map { entry -> real[entry.name] ?: StubTool(entry.name) }
        return filterTools(tools, flags)
    }

    /** The catalogue's own entry for a tool. */
    private fun entry(name: String): McpToolCatalog.ToolEntry = McpToolCatalog.catalog.single { it.name == name }

    /** The script of the scenario: the expected call, the mistaken call, the answer. */
    private fun script(scenario: Scenario): List<List<StreamFrame>> =
        listOf(
            listOf(
                StreamFrame.ToolCallComplete(id = "c1", name = scenario.expectedTool, content = scenario.expectedArgs),
            ),
            listOf(StreamFrame.ToolCallComplete(id = "c2", name = scenario.confusableTool, content = "{}")),
            listOf(StreamFrame.TextDelta("done")),
        )

    /** Runs one turn of the scenario against one toolbox and reports what happened. */
    private suspend fun runTurn(
        scenario: Scenario,
        tools: List<Tool<*, *>>,
    ): Turn {
        val model = ScriptedModel(script(scenario))
        val events =
            ToolCallLoop(tools = tools)
                .run(
                    prompt(Prompt.Empty, KoogClock.System) {
                        system("you are a test")
                        user(scenario.prompt)
                    },
                ) { _, _ -> model.next() }
                .toList()

        // Each call is announced (RUNNING) and then reported (COMPLETED or FAILED);
        // the last event of a call is its outcome.
        val finalStatus =
            events
                .filterIsInstance<AgentEvent.ToolCallUpdated>()
                .associate { it.id to it.status }

        val namesById =
            events
                .filterIsInstance<AgentEvent.ToolCallUpdated>()
                .associate { it.id to it.name }

        return Turn(
            offered = tools.map { it.descriptor.name },
            calls = finalStatus.size,
            executed = finalStatus.filterValues { it == ToolCallStatus.COMPLETED }.keys.map { namesById.getValue(it) },
            refused = finalStatus.filterValues { it == ToolCallStatus.FAILED }.keys.map { namesById.getValue(it) },
        )
    }

    @Test
    fun `the expected tool is reachable and honoured in both conditions`() =
        runTest {
            scenarios.forEach { scenario ->
                val full = runTurn(scenario, toolbox(scope = null))
                val scoped = runTurn(scenario, toolbox(scope = scenario.domains))

                listOf("full" to full, "scoped" to scoped).forEach { (label, turn) ->
                    assertTrue(
                        scenario.expectedTool in turn.offered,
                        "${scenario.title}: the $label toolbox must offer ${scenario.expectedTool}",
                    )
                    assertTrue(
                        scenario.expectedTool in turn.executed,
                        "${scenario.title}: the $label toolbox must honour ${scenario.expectedTool}",
                    )
                }
            }
        }

    @Test
    fun `the full toolbox honours a cross-domain call that the scoped toolbox refuses`() =
        runTest {
            scenarios.forEach { scenario ->
                val full = runTurn(scenario, toolbox(scope = null))
                val scoped = runTurn(scenario, toolbox(scope = scenario.domains))

                assertTrue(
                    scenario.confusableTool in full.executed,
                    "${scenario.title}: the full toolbox reaches ${scenario.confusableTool}",
                )
                assertFalse(
                    scenario.confusableTool in scoped.offered,
                    "${scenario.title}: the scoped toolbox must not offer ${scenario.confusableTool}",
                )
                assertTrue(
                    scenario.confusableTool in scoped.refused,
                    "${scenario.title}: the scoped toolbox must refuse ${scenario.confusableTool}, " +
                        "it answered ${scoped.executed} instead",
                )

                // The script is the same in both conditions, so the model asks the same
                // number of times. What differs is what a call can reach — not how often
                // the model asks.
                assertEquals(
                    full.calls,
                    scoped.calls,
                    "${scenario.title}: the number of calls is the model's, not the toolbox's",
                )
            }
        }

    @Test
    fun `the scoped toolbox is narrower and exposes no write tool of another domain`() =
        runTest {
            scenarios.forEach { scenario ->
                val full = toolbox(scope = null)
                val scoped = toolbox(scope = scenario.domains)

                assertTrue(
                    scoped.size < full.size,
                    "${scenario.title}: ${scoped.size} tools is not narrower than ${full.size}",
                )

                // The number the decision note publishes, asserted so the two cannot
                // drift apart unnoticed.
                assertEquals(
                    scenario.expectedScopedSize,
                    scoped.size,
                    "${scenario.title}: the scoped toolbox offers ${scoped.size} tools, " +
                        "the decision note says ${scenario.expectedScopedSize}",
                )

                // The cross-cutting domain is part of every scope: the note's claim that a
                // scope stays wide because of it is only true if it is really there.
                val crossCutting =
                    McpToolCatalog.catalog
                        .filter { it.domain == CROSS_CUTTING_DOMAIN }
                        .map { it.name }
                val scopedNames = scoped.map { it.descriptor.name }
                assertTrue(
                    crossCutting.all { it in scopedNames },
                    "${scenario.title}: a scope must carry the $CROSS_CUTTING_DOMAIN domain, " +
                        "it is missing ${crossCutting - scopedNames.toSet()}",
                )

                val writeBehavior = McpToolCatalog.catalog.associate { it.name to it.writeBehavior }
                val scopedWrites =
                    scoped
                        .map { it.descriptor.name }
                        .filter { writeBehavior[it] != WriteBehavior.READ_ONLY }

                assertTrue(
                    scopedWrites.all { name -> entry(name).domain in scenario.domains },
                    "${scenario.title}: a scoped toolbox must not carry a write tool of another domain: $scopedWrites",
                )
            }
        }

    @Test
    fun `a tool switched off by the kill switch stays off in a scoped toolbox`() =
        runTest {
            val flags = mapOf("delete_note" to false, "list_projects" to false)

            val scoped = toolbox(scope = setOf("notes", "projects"), flags = flags)

            assertFalse("delete_note" in scoped.map { it.descriptor.name }, "a disabled tool must stay disabled")
            assertFalse("list_projects" in scoped.map { it.descriptor.name }, "a disabled tool must stay disabled")
            assertTrue("get_note" in scoped.map { it.descriptor.name }, "the tools that are on must still be there")
        }

    @Test
    fun `the write-proposal workflow proposes and writes nothing in both conditions`() =
        runTest {
            // Each condition gets its own data, so one condition cannot leave a proposal
            // behind for the other one to be counted as.
            listOf("full" to null, "scoped" to setOf("tasks")).forEach { (label, scope) ->
                val world = WriteWorld()
                val scenario = world.scenario()

                val turn = runTurn(scenario, toolbox(scope = scope, real = mapOf("update_task" to world.tool)))

                assertTrue(
                    "update_task" in turn.executed,
                    "$label: the update must be proposed, the turn answered ${turn.executed}",
                )

                // The proposal is the whole point: the task must not have changed, and the
                // user must have exactly one thing to confirm.
                assertEquals("Buy groceries", world.storedTitle(), "the task itself must not have changed")
                assertEquals(1, world.waitingProposals(), "exactly one proposal to confirm")
            }

            assertEquals(
                WriteBehavior.PROPOSAL_WRITE,
                entry("update_task").writeBehavior,
                "the write boundary is the tool's, and scoping does not change it",
            )
        }
}

/**
 * A world of fakes for the write-proposal scenario: one task, and the tool that
 * proposes a change to it.
 */
private class WriteWorld {
    private val clock = FakeClock()
    private val user: UserId = TestUsers.DEFAULT
    private val currentUser = FakeProfileAwareCurrentUser(user)
    private val proposals = FakeProposalRepository(clock)
    private val tasks = FakeTaskRepository()

    private val task =
        Task(
            id = TaskId.generate(),
            title = "Buy groceries",
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            createdAt = clock.now(),
            updatedAt = clock.now(),
            userId = user,
        )

    val tool: UpdateTaskTool = UpdateTaskTool(proposals, tasks, currentUser, clock)

    init {
        tasks.seed(task)
    }

    fun scenario(): Scenario =
        Scenario(
            title = "task update",
            prompt = "Rename my task \"Buy groceries\" to \"Buy milk\".",
            domains = setOf("tasks"),
            expectedTool = "update_task",
            expectedArgs = """{"taskId":"${task.id.value}","title":"Buy milk"}""",
            confusableTool = "delete_note",
            expectedScopedSize = 25,
        )

    suspend fun storedTitle(): String? = tasks.observe(TaskId(task.id.value)).first()?.title

    suspend fun waitingProposals(): Int = proposals.watchWaitingProposals().first().size
}
