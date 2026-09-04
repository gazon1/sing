package com.singularity.todo.feature.ai.chat

import com.singularity.todo.feature.ai.FakeTextGen
import com.singularity.todo.feature.ai.TextGenPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(flow: Flow<String> = flowOf("Hi ", "there!")) =
        ChatViewModel(ScriptedTextGen(flow))

    @Test
    fun `send appends user and assistant placeholder`() = runTest {
        val vm = newVm()
        vm.onIntent(ChatViewModel.Intent.InputChanged("Hello"))
        vm.onIntent(ChatViewModel.Intent.Send)

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
        assertEquals("Hello", messages[0].content)
        assertEquals(ChatRole.Assistant, messages[1].role)
    }

    @Test
    fun `send streams chunks into assistant message`() = runTest {
        val vm = newVm(flowOf("alpha", " beta", " gamma"))
        vm.onIntent(ChatViewModel.Intent.InputChanged("x"))
        vm.onIntent(ChatViewModel.Intent.Send)

        val assistant = vm.uiState.value.messages.last { it.role == ChatRole.Assistant }
        assertEquals("alpha beta gamma", assistant.content)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `send ignores blank input`() = runTest {
        val vm = newVm()
        vm.onIntent(ChatViewModel.Intent.InputChanged("   "))
        vm.onIntent(ChatViewModel.Intent.Send)

        assertTrue(vm.uiState.value.messages.isEmpty())
    }

    @Test
    fun `FakeTextGen returns single placeholder chunk`() = runTest {
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
}
