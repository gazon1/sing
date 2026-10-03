@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai.chat

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.feature.ai.FakeTextGen
import com.singularity.todo.feature.ai.TextGenPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testLog = Logger.withTag("ChatViewModelTest")

    private fun newVm(scope: CoroutineScope, flow: Flow<String> = flowOf("Hi ", "there!")) = ChatViewModel(
        testLog,
        ScriptedTextGen(flow),
        SequenceIdGenerator(),
        AutoCloseableCoroutineScope(scope.coroutineContext),
    )

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

/** Test double for [TextGenPort] that emits a scripted [Flow]. */
private class ScriptedTextGen(private val scripted: Flow<String>) : TextGenPort {
    override suspend fun generate(prompt: String, systemPrompt: String?, model: String?): Result<String> =
        Result.success("unused")

    override fun streamChat(message: String): Flow<String> = scripted

    override suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = Result.success(emptyList())
}
