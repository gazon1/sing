package com.singularity.todo.feature.ai

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelResolverTest {

    @Test fun `resolves all known model ids`() {
        assertEquals(OpenAIModels.Chat.GPT4o, resolveModel("gpt-4o"))
        assertEquals(OpenAIModels.Chat.GPT4oMini, resolveModel("gpt-4o-mini"))
        assertEquals(OpenAIModels.Chat.GPT4_1, resolveModel("gpt-4.1"))
        assertEquals(OpenAIModels.Chat.GPT4_1Nano, resolveModel("gpt-4.1-nano"))
        assertEquals(OpenAIModels.Chat.GPT4_1Mini, resolveModel("gpt-4.1-mini"))
        assertEquals(OpenAIModels.Chat.O1, resolveModel("o1"))
        assertEquals(OpenAIModels.Chat.O3, resolveModel("o3"))
        assertEquals(OpenAIModels.Chat.O3Mini, resolveModel("o3-mini"))
        assertEquals(OpenAIModels.Chat.O4Mini, resolveModel("o4-mini"))
        assertEquals(OpenAIModels.Chat.GPT5, resolveModel("gpt-5"))
        assertEquals(OpenAIModels.Chat.GPT5Mini, resolveModel("gpt-5-mini"))
    }

    @Test fun `unknown id falls back to GPT4oMini`() {
        assertEquals(OpenAIModels.Chat.GPT4oMini, resolveModel("claude-3"))
        assertEquals(OpenAIModels.Chat.GPT4oMini, resolveModel(""))
        assertEquals(OpenAIModels.Chat.GPT4oMini, resolveModel("???"))
    }
}