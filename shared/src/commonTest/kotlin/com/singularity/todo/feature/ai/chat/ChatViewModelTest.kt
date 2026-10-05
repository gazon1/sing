@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.chat

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.feature.ai.FakeTextGen
import com.singularity.todo.feature.ai.TextGenPort
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.core.A2uiMessageProcessor
import com.singularity.todo.feature.genui.core.A2uiValidator
import com.singularity.todo.feature.genui.engine.GenuiSession
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.transport.GenuiTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

    private fun newVm(scope: CoroutineScope, flow: Flow<String> = flowOf("Hi ", "there!")) = ChatViewModel(
        testLog,
        ScriptedTextGen(flow),
        SequenceIdGenerator(),
        scope = AutoCloseableCoroutineScope(scope.coroutineContext),
    )

    private fun newGenuiVm(
        scope: CoroutineScope,
        answers: List<String>,
    ): Pair<ChatViewModel, ScriptedGenuiTransport> {
        val transport = ScriptedGenuiTransport(answers)
        val controller = SurfaceController()
        val parser = A2uiParser()
        val vm = ChatViewModel(
            testLog,
            ScriptedTextGen(flowOf("unused")),
            SequenceIdGenerator(),
            genui = GenuiSession(
                transport,
                parser,
                A2uiMessageProcessor(A2uiValidator(SingularityCatalog), controller),
                controller,
            ),
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )
        return vm to transport
    }

    // ─── A surface is part of the conversation, not a picture of it ───────

    @Test
    fun anAnswerWithAScreenCarriesItInTheMessage() = runTest {
        val (vm, _) = newGenuiVm(this, listOf(SURFACE))
        vm.onIntent(ChatViewModel.Intent.InputChanged("show my tasks"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val reply = vm.state.value.messages.last()
        assertTrue(reply.content.isNotBlank(), "The answer still has its words")
        assertTrue(reply.surfaceId != null, "And the screen it drew")
    }

    @Test
    fun aProseOnlyAnswerIsACompleteAnswer() = runTest {
        val (vm, _) = newGenuiVm(this, listOf("Three things are due today.\n"))
        vm.onIntent(ChatViewModel.Intent.InputChanged("what is due?"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val reply = vm.state.value.messages.last()
        assertEquals("Three things are due today.", reply.content)
        assertTrue(reply.surfaceId == null, "No screen was asked for, none is invented")
    }

    @Test
    fun twoAnswersDoNotShareOneScreen() = runTest {
        val (vm, _) = newGenuiVm(this, listOf(SURFACE, SURFACE))
        vm.onIntent(ChatViewModel.Intent.InputChanged("show my tasks"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()
        vm.onIntent(ChatViewModel.Intent.InputChanged("and overdue"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val withSurfaces = vm.state.value.messages.filter { it.surfaceId != null }
        assertEquals(2, withSurfaces.size)
        assertTrue(withSurfaces[0].surfaceId != withSurfaces[1].surfaceId, "One screen per answer")
    }

    @Test
    fun aPressInsideAScreenIsTheNextTurnOfTheConversation() = runTest {
        val (vm, transport) = newGenuiVm(this, listOf(SURFACE, SURFACE))
        vm.onIntent(ChatViewModel.Intent.InputChanged("show my tasks"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        vm.onIntent(ChatViewModel.Intent.SurfaceAction("open_task", null))
        advanceUntilIdle()

        assertEquals(2, transport.requests.size, "The press reached the model")
        val followUp = transport.requests[1]
        assertTrue("open_task" in followUp, "By the name the model gave the control: $followUp")
        assertTrue("show my tasks" in followUp, "In the context of the conversation: $followUp")

        // The press is not something the user typed, so it does not become their message.
        val userMessages = vm.state.value.messages.filter { it.role == ChatRole.User }
        assertEquals(1, userMessages.size, "A protocol event is not a thing the user said")
    }

    @Test
    fun aPressCarriesWhatTheControlWasBoundTo() = runTest {
        val (vm, transport) = newGenuiVm(this, listOf(SURFACE, SURFACE))
        vm.onIntent(ChatViewModel.Intent.InputChanged("show my tasks"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val bound = JsonObject(mapOf("taskId" to JsonPrimitive("t-42")))
        vm.onIntent(ChatViewModel.Intent.SurfaceAction("open_task", bound))
        advanceUntilIdle()

        assertTrue("t-42" in transport.requests[1], "The model needs to know which task: ${transport.requests[1]}")
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

        val messages = vm.state.value.messages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
        assertEquals("Hello", messages[0].content)
        assertEquals(ChatRole.Assistant, messages[1].role)
    }

    @Test
    fun sendStreamsChunksIntoAssistantMessage() = runTest {
        val vm = newVm(this, flowOf("alpha", " beta", " gamma"))
        vm.onIntent(ChatViewModel.Intent.InputChanged("x"))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        val assistant = vm.state.value.messages.last { it.role == ChatRole.Assistant }
        assertEquals("alpha beta gamma", assistant.content)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun sendIgnoresBlankInput() = runTest {
        val vm = newVm(this)
        vm.onIntent(ChatViewModel.Intent.InputChanged("   "))
        vm.onIntent(ChatViewModel.Intent.Send)
        advanceUntilIdle()

        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun fakeTextGenReturnsSinglePlaceholderChunk() = runTest {
        val chunks = FakeTextGen().streamChat("hi").take(10).toList()
        assertEquals(1, chunks.size)
        assertTrue(chunks.first().startsWith("("))
    }
}

/**
 * A transport that answers with whole messages, one per call.
 *
 * Whole messages rather than fragments: what is being tested here is what the chat does with an
 * answer, and fragmentation is the session's business, tested on its own.
 */
private class ScriptedGenuiTransport(private val answers: List<String>) : GenuiTransport {
    val requests: MutableList<String> = mutableListOf()

    override suspend fun send(prompt: String, systemPrompt: String): Flow<String> {
        requests += prompt
        return flowOf(answers.getOrElse(requests.size - 1) { "" })
    }
}

/** Test double for [TextGenPort] that emits a scripted [Flow]. */
private class ScriptedTextGen(private val scripted: Flow<String>) : TextGenPort {
    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String): Flow<String> = scripted

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = Result.success(emptyList())
}
