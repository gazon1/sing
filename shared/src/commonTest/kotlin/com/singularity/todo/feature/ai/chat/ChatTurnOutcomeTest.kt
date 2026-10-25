@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.chat

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentPlanStep
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.llm.PlanStepStatus
import com.singularity.todo.core.llm.ToolCallStatus
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.core.GenuiRejectionCounter
import com.singularity.todo.feature.genui.engine.SurfaceTurnFactory
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.test.fakes.SequenceIdGenerator
import com.singularity.todo.test.helpers.TestVmContext
import com.singularity.todo.test.helpers.testVmContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How a turn ends.
 *
 * The guarantee under test: a turn reaches **exactly one** terminal outcome, and the outcome
 * distinguishes "the agent answered" from "the agent did not answer" from "the user stopped
 * it". A turn that ended without an outcome would leave the reader watching a spinner that
 * no longer has anything behind it, and a turn that ended *successfully* when the stream
 * died would tell the reader the answer is complete when it is not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class ChatTurnOutcomeTest {

    private val testLog = Logger.withTag("ChatTurnOutcomeTest")

    private fun TestScope.chat(events: Flow<AgentEvent>): TestVmContext<ChatViewModel, ChatViewModel.State> =
        testVmContext(
            vm = ChatViewModel(
                log = testLog,
                agent = OutcomeTextGen(events),
                idGen = SequenceIdGenerator(),
                scope = AutoCloseableCoroutineScope(coroutineContext),
            ),
            stateOf = { it.stateFlow },
            scope = this,
            backgroundScope = backgroundScope,
        )

    /**
     * A chat wired the way production wires it: the model may draw a screen.
     *
     * The wiring is the point. A card that only appears on a path the application never takes is
     * a card nobody ever sees, and the defect this guards against is invisible to a test that
     * wires the chat differently.
     */
    private fun TestScope.chatWithScreen(
        agent: TextGenPort,
    ): TestVmContext<ChatViewModel, ChatViewModel.State> {
        val controller = SurfaceController()
        return testVmContext(
            vm = ChatViewModel(
                log = testLog,
                agent = agent,
                idGen = SequenceIdGenerator(),
                surfaces = SurfaceTurnFactory(
                    A2uiParser(),
                    A2uiMessageProcessor(A2uiValidator(SingularityCatalog), controller),
                    controller,
                    SingularityCatalog,
                    GenuiRejectionCounter(),
                ),
                scope = AutoCloseableCoroutineScope(coroutineContext),
            ),
            stateOf = { it.stateFlow },
            scope = this,
            backgroundScope = backgroundScope,
        )
    }

    private suspend fun TestVmContext<ChatViewModel, ChatViewModel.State>.ask() {
        act {
            onIntent(ChatViewModel.Intent.InputChanged("hello"))
            onIntent(ChatViewModel.Intent.Send)
        }
    }

    @Test
    fun aTurnThatAnswersIsCompleted() = runTest {
        val ctx = chat(flowOf(AgentEvent.TextDelta("hi"), AgentEvent.TurnCompleted))

        ctx.ask()

        assertIs<AgentTurnOutcome.Completed>(ctx.state.value.turnOutcome)
    }

    @Test
    fun aTurnWhoseStreamDiesIsFailedNotCompleted() = runTest {
        // The stream ends without the completion event: the agent did not finish.
        val ctx = chat(flowOf(AgentEvent.TextDelta("half an ans")))

        ctx.ask()

        val outcome = ctx.state.value.turnOutcome
        assertIs<AgentTurnOutcome.Failed>(outcome, "A stream that died is a failure: $outcome")
    }

    @Test
    fun aFailedTurnIsNotLoading() = runTest {
        val ctx = chat(flowOf(AgentEvent.TextDelta("half an ans")))

        ctx.ask()

        assertFalse(ctx.state.value.isLoading, "The spinner is gone")
    }

    @Test
    fun aCompletedTurnIsNotLoading() = runTest {
        val ctx = chat(flowOf(AgentEvent.TextDelta("hi"), AgentEvent.TurnCompleted))

        ctx.ask()

        assertFalse(ctx.state.value.isLoading, "The spinner is gone")
    }

    @Test
    fun thePartialAnswerSurvivesAFailedTurn() = runTest {
        val ctx = chat(flowOf(AgentEvent.TextDelta("half an ans")))

        ctx.ask()

        val reply = ctx.state.value.messages.last()
        assertEquals("half an ans", reply.content, "What was said before the failure is still there")
    }

    @Test
    fun theOutcomeIsSetExactlyOnce() = runTest {
        val ctx = chat(flowOf(AgentEvent.TextDelta("hi"), AgentEvent.TurnCompleted))

        ctx.ask()

        val first = ctx.state.value.turnOutcome
        // A late event after the turn ended must not change how it ended.
        ctx.act { onIntent(ChatViewModel.Intent.InputChanged("again")) }

        assertEquals(first, ctx.state.value.turnOutcome, "The outcome does not change under a late event")
    }

    @Test
    fun aToolCallIsVisibleInTheState() = runTest {
        val ctx = chat(
            flowOf(
                AgentEvent.ToolCallUpdated("c1", "list_tasks", ToolCallStatus.RUNNING),
                AgentEvent.ToolCallUpdated("c1", "list_tasks", ToolCallStatus.COMPLETED, "3 tasks"),
                AgentEvent.TurnCompleted,
            ),
        )

        ctx.ask()

        val calls = ctx.state.value.activity.calls
        assertEquals(1, calls.size, "One card, not two: $calls")
        assertEquals(ToolCallStatus.COMPLETED, calls.single().status, "With the outcome")
        assertEquals("3 tasks", calls.single().summary, "And the summary")
    }

    @Test
    fun aFailedToolCallIsVisibleAsFailed() = runTest {
        val ctx = chat(
            flowOf(
                AgentEvent.ToolCallUpdated("c1", "get_task", ToolCallStatus.FAILED, "no such task"),
                AgentEvent.TurnCompleted,
            ),
        )

        ctx.ask()

        val call = ctx.state.value.activity.calls.single()
        assertEquals(ToolCallStatus.FAILED, call.status, "The failure is shown, not swallowed")
    }

    @Test
    fun thePlanIsVisibleInTheState() = runTest {
        val ctx = chat(
            flowOf(
                AgentEvent.PlanUpdated(
                    listOf(AgentPlanStep("read the notes", PlanStepStatus.PENDING)),
                ),
                AgentEvent.TurnCompleted,
            ),
        )

        ctx.ask()

        assertEquals(1, ctx.state.value.activity.plan.size, "The plan is shown")
    }

    @Test
    fun aNewTurnClearsThePreviousActivity() = runTest {
        val first = chat(
            flowOf(
                AgentEvent.ToolCallUpdated("c1", "list_tasks", ToolCallStatus.COMPLETED, "3 tasks"),
                AgentEvent.TurnCompleted,
            ),
        )
        first.ask()
        assertNotNull(first.state.value.activity.calls.singleOrNull(), "The first turn had a call")

        val second = chat(
            flowOf(
                AgentEvent.ToolCallUpdated("c2", "get_task", ToolCallStatus.RUNNING),
                AgentEvent.TurnCompleted,
            ),
        )
        second.ask()

        val calls = second.state.value.activity.calls
        assertEquals(1, calls.size, "The second turn's activity, not both turns'")
        assertEquals("c2", calls.single().id, "The second turn's call")
    }

    @Test
    fun aTurnThatProducedNothingIsFailed() = runTest {
        // The user sent something and the agent produced nothing at all — not even a
        // partial answer. "Failed" is the honest outcome: the turn did not finish.
        val ctx = chat(flowOf())

        ctx.ask()

        val outcome = ctx.state.value.turnOutcome
        assertIs<AgentTurnOutcome.Failed>(outcome, "A turn that produced nothing is a failure: $outcome")
    }

    @Test
    fun aStoppedTurnIsCancelledAndKeepsWhatItSaid() = runTest {
        val agent = SuspendingTextGen()
        val ctx = chat(agent.flow)

        ctx.ask()
        agent.started.await()
        ctx.act { onIntent(ChatViewModel.Intent.Stop) }
        advanceUntilIdle()

        val outcome = ctx.state.value.turnOutcome
        assertIs<AgentTurnOutcome.Cancelled>(outcome, "A turn the reader stopped is cancelled: $outcome")
        assertFalse(ctx.state.value.isLoading, "The spinner is gone")
        assertEquals(
            "half an ans",
            ctx.state.value.messages.last().content,
            "What was said before the stop is still there",
        )
    }

    @Test
    fun aToolCallThroughTheScreenWiringIsVisible() = runTest {
        // The wiring production uses: one model call, the screen drawn from what it wrote.
        // A card that only appears on the other wiring is a card nobody ever sees.
        val ctx = chatWithScreen(
            OutcomeTextGen(
                flowOf(
                    AgentEvent.ToolCallUpdated("c1", "list_tasks", ToolCallStatus.RUNNING),
                    AgentEvent.ToolCallUpdated("c1", "list_tasks", ToolCallStatus.COMPLETED, "3 tasks"),
                    AgentEvent.TurnCompleted,
                ),
            ),
        )

        ctx.ask()

        val calls = ctx.state.value.activity.calls
        assertEquals(1, calls.size, "One card, not two: $calls")
        assertEquals("3 tasks", calls.single().summary, "With the summary")
        assertIs<AgentTurnOutcome.Completed>(ctx.state.value.turnOutcome)
    }

    @Test
    fun theScreenIsOfferedToTheModelAsThatCallsInstructions() = runTest {
        val agent = RecordingTextGen(flowOf(AgentEvent.TurnCompleted))
        val ctx = chatWithScreen(agent)

        ctx.ask()

        assertTrue(
            agent.instructionsSeen.firstOrNull()?.contains("Components") == true,
            "The catalogue reaches the model: ${agent.instructionsSeen}",
        )
    }

    /**
     * A provider that is not configured ends the turn failed (REQ-CHTURN-006).
     *
     * `KoogAgentService.streamChat` emits exactly this when there is no API key: the
     * screen's own words, and then no completion event at all — the turn did not finish,
     * it never started. The test asserts the rule the turn relies on rather than
     * instantiating the service, which is wired to settings, tools and a remote config.
     */
    @Test
    fun aProviderWithNoCredentialsEndsTheTurnFailed() = runTest {
        val ctx = chat(
            flowOf(
                AgentEvent.TextDelta(
                    "(AI unavailable: API key not configured. Set it in Settings > AI Provider.)",
                ),
            ),
        )

        ctx.ask()

        val outcome = ctx.state.value.turnOutcome
        assertIs<AgentTurnOutcome.Failed>(outcome, "No completion event is no completion: $outcome")
        assertTrue(
            ctx.state.value.messages.last().content.contains("not configured"),
            "And the screen keeps saying why: ${ctx.state.value.messages.last().content}",
        )
    }

    /**
     * The turn completes only on the strength of its *last* attempt.
     *
     * A correction is part of the turn. An answer that was corrected and then cut off is
     * not a complete answer, so the first attempt's completion must not leak out as the
     * turn's — the screen would tell the reader the answer is whole when it is not.
     */
    @Test
    fun aTruncatedFinalAttemptFailsTheTurnEvenIfAnEarlierAttemptCompleted() = runTest {
        val agent = PerCallTextGen(
            listOf(
                // Attempt 1: a completion, and a screen the parser rejects — so the turn
                // is corrected, not finished.
                flowOf(
                    AgentEvent.TextDelta(
                        """{"createSurface":{"surfaceId":"test","rootId":"chart","components":[""" +
                            """{"id":"chart","kind":"chart","series":[1,2,3]}]}}""" + "\n",
                    ),
                    AgentEvent.TurnCompleted,
                ),
                // Attempt 2: text only, and the stream dies before the completion arrives.
                flowOf(AgentEvent.TextDelta("here is a sentence")),
            ),
        )
        val ctx = chatWithScreen(agent)

        ctx.ask()

        val outcome = ctx.state.value.turnOutcome
        assertIs<AgentTurnOutcome.Failed>(outcome, "The last attempt did not finish: $outcome")
        assertEquals(2, agent.calls, "The rejection caused exactly one correction")
    }
}

/** A text generator that answers a different script on each call. */
private class PerCallTextGen(private val scripts: List<Flow<AgentEvent>>) : TextGenPort {
    var calls: Int = 0
        private set

    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String, instructions: String): Flow<AgentEvent> {
        val script = scripts.getOrElse(calls) { flowOf() }
        calls++
        return script
    }

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = Result.success(emptyList())
}

/** A text generator that emits a scripted [Flow]. */
private class OutcomeTextGen(private val scripted: Flow<AgentEvent>) : TextGenPort {
    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String, instructions: String): Flow<AgentEvent> = scripted

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = Result.success(emptyList())
}

/** A text generator that records what it was asked and what it was told. */
private class RecordingTextGen(private val scripted: Flow<AgentEvent>) : TextGenPort {
    val instructionsSeen: MutableList<String> = mutableListOf()

    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String, instructions: String): Flow<AgentEvent> = flow {
        instructionsSeen += instructions
        scripted.collect { event: AgentEvent -> emit(event) }
    }

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = Result.success(emptyList())
}

/**
 * A model that says something and then never finishes.
 *
 * A turn that ends on its own cannot be stopped, so this one waits until the reader stops it.
 */
private class SuspendingTextGen {
    val started: CompletableDeferred<Unit> = CompletableDeferred()
    val flow: Flow<AgentEvent> = flow {
        emit(AgentEvent.TextDelta("half an ans"))
        started.complete(Unit)
        awaitCancellation()
    }
}
