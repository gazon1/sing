package com.singularity.todo.feature.ai.chat

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.TextGenPort
import com.singularity.todo.test.fakes.FakeIdGenerator
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.TIMEOUT_MS
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.dragChatMessagesDown
import com.singularity.todo.test.helpers.dragChatMessagesUp
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Tag
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * The chat's follow-the-tail policy, driven through the screen with a reply that
 * arrives one token at a time.
 *
 * ## Why the screen and not the policy alone
 *
 * `ChatFollowTailTest` mounts the policy on a list of its own, which is the right shape
 * for the two things a ViewModel cannot arrange — a restored conversation and a reply
 * taller than the window. Everything else here is about the *conversation moving*: the
 * policy has to see a message grow, the reader has to be able to leave the end, and a
 * question of their own has to bring them back. That is the screen's state, so the test
 * drives the screen.
 *
 * ## Why the tokens are pushed by hand
 *
 * A model that answers in one piece is not a stream, and a policy that only ever sees a
 * conversation arrive whole is a policy nothing exercises. [SteppedTextGen] hands the
 * ViewModel a channel instead of a script: the test decides when the next token exists,
 * so "the reader is parked" and "a token arrives" are two separate, observable moments.
 *
 * ## Why the assertions are about text
 *
 * A lazy list does not compose what is off screen, so the presence of a message's text in
 * the semantics tree *is* the answer to "is it on screen". Asserting on scroll offsets
 * would be asserting on the policy's implementation; asserting on the bubble is asserting
 * on what the reader can see.
 *
 * The assertions are exact whole-bubble matches on purpose: the bubble's text is the
 * whole reply, so an exact match fails the moment the bubble is composed — which is what
 * makes [assertTextNotExists] an assertion that can fail rather than a tautology.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class ChatStreamingFollowTest {
    @Test
    fun a_reader_parked_on_an_earlier_message_is_not_moved_by_the_tokens() =
        runIsolatedComposeTest(width = WIDTH, height = HEIGHT) {
            val streams = SteppedTextGen()
            val scope = AutoCloseableCoroutineScope(SupervisorJob() + Dispatchers.Default)
            val viewModel = chatViewModel(streams, scope)
            try {
                setContent { ScreenWithOwners { ChatScreenContent(vm = viewModel) } }

                // A conversation long enough to scroll: one question, one reply several
                // times taller than the window.
                ask(viewModel, FIRST_QUESTION)
                awaitTurn(streams, turn = 1)
                streams.send(AgentEvent.TextDelta(HISTORY_REPLY))
                streams.send(AgentEvent.TurnCompleted)
                awaitReply(viewModel, HISTORY_REPLY)
                assertTextDisplayed(HISTORY_REPLY)

                // The reply this test is about, arriving one token at a time.
                ask(viewModel, SECOND_QUESTION)
                awaitTurn(streams, turn = 2)
                streams.send(AgentEvent.TextDelta("the first "))
                streams.send(AgentEvent.TextDelta("second "))
                streams.send(AgentEvent.TextDelta("token"))
                awaitReply(viewModel, STREAMED_SO_FAR)
                assertTextDisplayed(STREAMED_SO_FAR)

                // The reader scrolls up to re-read an older answer.
                dragChatMessagesDown()
                assertTextNotExists(STREAMED_SO_FAR)

                // A token arrives while they are reading. They are left where they put
                // themselves: a list that re-aligned on every token would bring the bubble
                // back on screen and fail here.
                streams.send(AgentEvent.TextDelta(" and a fourth"))
                awaitReply(viewModel, WITH_FOURTH)
                assertTextNotExists(WITH_FOURTH)

                // Back at the end, the end is picked up again on the next token.
                dragChatMessagesUp()
                streams.send(AgentEvent.TextDelta(" and a fifth"))
                awaitReply(viewModel, WITH_FIFTH)
                assertTextDisplayed(WITH_FIFTH)

                // Ends the turn, so the reader can ask a question of their own.
                streams.send(AgentEvent.TurnCompleted)
                waitUntil(timeoutMillis = TIMEOUT_MS) { !viewModel.stateFlow.value.isLoading }

                // A question asked from mid-history is a request to see its answer: the
                // conversation moves to the message the reader just sent.
                dragChatMessagesDown()
                assertTextNotExists(WITH_FIFTH)
                ask(viewModel, THIRD_QUESTION)
                awaitTurn(streams, turn = 3)
                assertTextDisplayed(THIRD_QUESTION)
            } finally {
                scope.close()
            }
        }

    /**
     * A conversation shorter than the window is not scrolled at all.
     *
     * Both ends stay on screen: the first message is not pushed off the top by an
     * alignment to the last one's start, and the last one is not scrolled past. The
     * window is small on purpose — at 1024×768 almost any conversation fits, and a test
     * that only fits there says nothing.
     */
    @Test
    fun a_conversation_that_fits_is_not_scrolled() =
        runIsolatedComposeTest(width = WIDTH, height = HEIGHT) {
            val streams = SteppedTextGen()
            val scope = AutoCloseableCoroutineScope(SupervisorJob() + Dispatchers.Default)
            val viewModel = chatViewModel(streams, scope)
            try {
                setContent { ScreenWithOwners { ChatScreenContent(vm = viewModel) } }

                ask(viewModel, FIRST_QUESTION)
                awaitTurn(streams, turn = 1)
                streams.send(AgentEvent.TextDelta(SHORT_REPLY))
                streams.send(AgentEvent.TurnCompleted)
                awaitReply(viewModel, SHORT_REPLY)

                assertTextDisplayed(FIRST_QUESTION)
                assertTextDisplayed(SHORT_REPLY)
            } finally {
                scope.close()
            }
        }

    /**
     * The ViewModel under test, on a scope the test closes.
     *
     * The scope is cancelled in a `finally`: a ViewModel whose turn is still collecting a
     * channel after the test ends keeps a coroutine alive for the rest of the run, and a
     * leaked collector is exactly what makes the next test's timing depend on this one.
     */
    private fun chatViewModel(
        streams: SteppedTextGen,
        scope: AutoCloseableCoroutineScope,
    ): ChatViewModel =
        ChatViewModel(
            log = Logger.withTag("ChatStreamingFollowTest"),
            agent = streams,
            idGen = FakeIdGenerator("msg"),
            scope = scope,
        )

    /** Sends [question] through the ViewModel's own intents, as the input bar's send does. */
    private fun ask(
        viewModel: ChatViewModel,
        question: String,
    ) {
        viewModel.onIntent(ChatViewModel.Intent.InputChanged(question))
        viewModel.onIntent(ChatViewModel.Intent.Send)
    }

    /**
     * Waits until the turn's stream has been asked for.
     *
     * The turn is launched, not run inline, so a token sent before this returns would be
     * handed to no channel. The count is the stream's own, not the ViewModel's state: the
     * messages are appended before the model is called, so state would say "the turn
     * started" a moment too early.
     */
    private fun DesktopComposeUiTest.awaitTurn(
        streams: SteppedTextGen,
        turn: Int,
    ) = waitUntil(timeoutMillis = TIMEOUT_MS) { streams.turnCount == turn }

    /**
     * Waits until the last message holds exactly [expected].
     *
     * A token is *buffered* by the fake, and the ViewModel folds it on its own dispatcher;
     * an assertion about what is on screen has to know the token has landed, otherwise
     * "not on screen" passes for the wrong reason — the screen has not been recomposed yet.
     */
    private fun DesktopComposeUiTest.awaitReply(
        viewModel: ChatViewModel,
        expected: String,
    ) = waitUntil(timeoutMillis = TIMEOUT_MS) {
        viewModel.stateFlow.value.messages
            .lastOrNull()
            ?.content == expected
    }

    /**
     * A model whose answer the test writes, one event at a time.
     *
     * Each call to [streamChat] opens a fresh channel; the flow completes when a
     * [AgentEvent.TurnCompleted] is written into it, which is how one turn ends while the
     * next can still be asked. The channel is unlimited so a token written before the
     * ViewModel has started collecting it is not dropped — the tests write a burst of
     * three tokens in a row.
     */
    private class SteppedTextGen : TextGenPort {
        private val turns = AtomicInteger()
        private val channels = CopyOnWriteArrayList<Channel<AgentEvent>>()

        /** How many turns have been started, so a test can wait for its own. */
        val turnCount: Int get() = turns.get()

        /** Writes [event] into the turn that is running. */
        fun send(event: AgentEvent) {
            val channel =
                channels.lastOrNull()
                    ?: error("No turn has been started; call ask() and awaitTurn() first.")
            channel.trySend(event)
        }

        override fun streamChat(
            message: String,
            instructions: String,
        ): Flow<AgentEvent> {
            val channel = Channel<AgentEvent>(Channel.UNLIMITED)
            channels += channel
            turns.incrementAndGet()
            return flow {
                for (event in channel) {
                    emit(event)
                    if (event is AgentEvent.TurnCompleted) return@flow
                }
            }
        }

        override suspend fun generate(
            prompt: String,
            systemPrompt: String?,
            model: String?,
        ): Result<String> = error("The chat under test only streams.")

        override suspend fun listModels(
            baseUrl: String,
            apiKey: String,
        ): Result<List<String>> = Result.success(emptyList())
    }

    private companion object {
        /** Narrow and short: a conversation has to overflow it without being enormous. */
        const val WIDTH = 720
        const val HEIGHT = 420

        const val FIRST_QUESTION = "What did I get done last week?"

        /** Several lines, so the first turn alone is taller than the window. */
        val HISTORY_REPLY: String =
            (1..30).joinToString("\n") { line -> "History line $line of a long answer." }

        const val SECOND_QUESTION = "And this week?"
        const val THIRD_QUESTION = "What should I do next?"

        const val STREAMED_SO_FAR = "the first second token"
        const val WITH_FOURTH = "the first second token and a fourth"
        const val WITH_FIFTH = "the first second token and a fourth and a fifth"

        const val SHORT_REPLY = "One task is due today."
    }
}
