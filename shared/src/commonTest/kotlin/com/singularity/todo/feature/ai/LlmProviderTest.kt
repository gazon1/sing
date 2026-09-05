package com.singularity.todo.feature.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmProviderTest {

    @Test fun `fromId returns provider for known id`() {
        assertEquals(LlmProvider.OPENAI, LlmProvider.fromId("openai"))
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, LlmProvider.fromId("openai-compatible"))
        assertEquals(LlmProvider.OLLAMA, LlmProvider.fromId("ollama"))
        assertEquals(LlmProvider.CUSTOM, LlmProvider.fromId("custom"))
    }

    @Test fun `fromId falls back to default for unknown id`() {
        assertEquals(LlmProvider.DEFAULT, LlmProvider.fromId("anthropic"))
        assertEquals(LlmProvider.DEFAULT, LlmProvider.fromId(""))
        assertEquals(LlmProvider.DEFAULT, LlmProvider.fromId("???"))
    }

    @Test fun `fromId falls back to default for null`() {
        assertEquals(LlmProvider.DEFAULT, LlmProvider.fromId(null))
    }

    @Test fun `default is OPENAI`() {
        assertEquals(LlmProvider.OPENAI, LlmProvider.DEFAULT)
    }

    @Test fun `every provider has a non-blank default URL except CUSTOM`() {
        LlmProvider.entries
            .filter { it != LlmProvider.CUSTOM }
            .forEach { assertEquals(expected = true, actual = it.defaultBaseUrl.isNotBlank(), message = "${it.id} has blank default URL") }
        assertEquals(expected = "", actual = LlmProvider.CUSTOM.defaultBaseUrl)
    }
}