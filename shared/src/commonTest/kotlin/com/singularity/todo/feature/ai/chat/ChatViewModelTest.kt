@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.chat

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.test.fakes.SequenceIdGenerator
import com.singularity.todo.test.fakes.FakeTextGen
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.core.GenuiRejectionCounter
import com.singularity.todo.feature.genui.engine.SurfaceTurnFactory
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class ChatViewModelTest {

    private val testLog = Logger.withTag("ChatViewModelTest")

    private fun newVm(
        scope: CoroutineScope,
        flow: Flow<AgentEvent> = flowOf(
            AgentEvent.TextDelta("Hi "),
            AgentEvent.TextDelta("there!"),
            AgentEvent.TurnCompleted,
        ),
    ) = ChatViewModel(
        testLog,
        ScriptedTextGen(flow),
        SequenceIdGenerator(),
        scope = AutoCloseableCoroutineScope(scope.coroutineContext),
    )

    /**
     * A chat that may draw a screen, driven by a model that answers with whole messages.
     *
     * The wiring is the production one — one model call, the screen drawn from what it wrote —
     * because a test that wires it differently proves nothing about the screen.
     */
    private fun newScreenVm(
        scope: CoroutineScope,
        answers: List<String>,
    ): Pair<ChatViewModel, ScriptedScreenTextGen> {
        val agent = ScriptedScreenTextGen(answers)
        val controller = SurfaceController()
        val vm = ChatViewModel(
            testLog,
            agent,
            SequenceIdGenerator(),
            surfaces = SurfaceTurnFactory(
                A2uiParser(),
                A2uiMessageProcessor(A2uiValidator(SingularityCatalog), controller),
                controller,
                SingularityCatalog,
                GenuiRejectionCounter(),
            ),
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )
        return vm to agent
    }

    // ─── A surface is part of the conversation, not a picture of it ───────

    @Test
    fun anAnswerWithAScreenCarriesItInTheMessage() = runTest {
        val (vm, _) = newScreenVm(this, listOf(SURFACE))
        vm.onIntent(ChatViewModel.Intent.InputChanged("show my tasks"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val reply = vm.stateFlow.value.messages.last()
        // The model wrote a screen and no words, so the message points at the screen and
        // says nothing: a sentence the model did not write would be worse than silence.
        assertTrue(reply.surfaceId != null, "And the screen it drew")
    }

    @Test
    fun aProseOnlyAnswerIsACompleteAnswer() = runTest {
        val (vm, _) = newScreenVm(this, listOf("Three things are due today.\n"))
        vm.onIntent(ChatViewModel.Intent.InputChanged("what is due?"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val reply = vm.stateFlow.value.messages.last()
        assertEquals("Three things are due today.", reply.content)
        assertTrue(reply.surfaceId == null, "No screen was asked for, none is invented")
    }

    @Test
    fun twoAnswersDoNotShareOneScreen() = runTest {
        val (vm, _) = newScreenVm(this, listOf(SURFACE, SURFACE))
        vm.onIntent(ChatViewModel.Intent.InputChanged("show my tasks"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()
        vm.onIntent(ChatViewModel.Intent.InputChanged("and overdue"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val withSurfaces = vm.stateFlow.value.messages.filter { it.surfaceId != null }
        assertEquals(2, withSurfaces.size)
        assertTrue(withSurfaces[0].surfaceId != withSurfaces[1].surfaceId, "One screen per answer")
    }

    @Test
    fun aPressInsideAScreenIsTheNextTurnOfTheConversation() = runTest {
        val (vm, agent) = newScreenVm(this, listOf(SURFACE, SURFACE))
        vm.onIntent(ChatViewModel.Intent.InputChanged("show my tasks"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        vm.onIntent(ChatViewModel.Intent.SurfaceAction("open_task", null))
        advanceUntilIdle()

        assertEquals(2, agent.requests.size, "The press reached the model")
        val followUp = agent.requests[1]
        assertTrue("open_task" in followUp, "By the name the model gave the control: $followUp")
        assertTrue("show my tasks" in followUp, "In the context of the conversation: $followUp")

        // The press is not something the user typed, so it does not become their message.
        val userMessages = vm.stateFlow.value.messages.filter { it.role == ChatRole.User }
        assertEquals(1, userMessages.size, "A protocol event is not a thing the user said")
    }

    @Test
    fun aPressCarriesWhatTheControlWasBoundTo() = runTest {
        val (vm, agent) = newScreenVm(this, listOf(SURFACE, SURFACE))
        vm.onIntent(ChatViewModel.Intent.InputChanged("show my tasks"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val bound = JsonObject(mapOf("taskId" to JsonPrimitive("t-42")))
        vm.onIntent(ChatViewModel.Intent.SurfaceAction("open_task", bound))
        advanceUntilIdle()

        assertTrue("t-42" in agent.requests[1], "The model needs to know which task: ${agent.requests[1]}")
    }

    private companion object {
        // A raw string does not process escapes, so the newline is appended rather than written
        // inside it: a literal backslash-n leaves the line unterminated, and the framer reports
        // that as a response cut off mid-message.
        const val SURFACE = """{"createSurface":{"surfaceId":"surface-1","rootId":"t1","components":[""" +
            """{"id":"t1","kind":"text","value":"Hello"}]}}""" + "\n"
    }

    @Test
    fun sendAppendsUserAndAssistantPlaceholder() = runTest {
        val vm = newVm(this)
        vm.onIntent(ChatViewModel.Intent.InputChanged("Hello"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val messages = vm.stateFlow.value.messages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
        assertEquals("Hello", messages[0].content)
        assertEquals(ChatRole.Assistant, messages[1].role)
    }

    @Test
    fun sendStreamsChunksIntoAssistantMessage() = runTest {
        val vm = newVm(
            this,
            flowOf(
                AgentEvent.TextDelta("alpha"),
                AgentEvent.TextDelta(" beta"),
                AgentEvent.TextDelta(" gamma"),
                AgentEvent.TurnCompleted,
            ),
        )
        vm.onIntent(ChatViewModel.Intent.InputChanged("x"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val assistant = vm.stateFlow.value.messages.last { it.role == ChatRole.Assistant }
        assertEquals("alpha beta gamma", assistant.content)
        assertFalse(vm.stateFlow.value.isLoading)
    }

    @Test
    fun sendIgnoresBlankInput() = runTest {
        val vm = newVm(this)
        vm.onIntent(ChatViewModel.Intent.InputChanged("   "))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        assertTrue(vm.stateFlow.value.messages.isEmpty())
    }

    @Test
    fun fakeTextGenReturnsSinglePlaceholderChunk() = runTest {
        val events = FakeTextGen().streamChat("hi").take(10).toList()
        assertEquals(2, events.size, "The answer and the end of the turn")
        val text = events.filterIsInstance<AgentEvent.TextDelta>().single().text
        assertTrue(text.startsWith("("))
    }
}

/**
 * A model that answers with whole messages, one per call.
 *
 * Whole messages rather than fragments: what is being tested here is what the chat does with an
 * answer, and fragmentation is the turn's business, tested on its own.
 */
private class ScriptedScreenTextGen(private val answers: List<String>) : TextGenPort {
    val requests: MutableList<String> = mutableListOf()

    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String, instructions: String): Flow<AgentEvent> = flow {
        requests += message
        emit(AgentEvent.TextDelta(answers.getOrElse(requests.size - 1) { "" }))
        emit(AgentEvent.TurnCompleted)
    }

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = Result.success(emptyList())
}

/** Test double for [TextGenPort] that emits a scripted [Flow]. */
private class ScriptedTextGen(private val scripted: Flow<AgentEvent>) : TextGenPort {
    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String, instructions: String): Flow<AgentEvent> = scripted

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = Result.success(emptyList())
}
