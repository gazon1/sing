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
import com.singularity.todo.test.fakes.SequenceIdGenerator
import com.singularity.todo.test.helpers.TestVmContext
import com.singularity.todo.test.helpers.testVmContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
}

/** A text generator that emits a scripted [Flow]. */
private class OutcomeTextGen(private val scripted: Flow<AgentEvent>) : TextGenPort {
    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String): Flow<AgentEvent> = scripted

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = Result.success(emptyList())
}
