package com.singularity.todo.feature.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmProviderTest {

    @Test fun fromIdReturnsProviderForKnownId() {
        assertEquals(LlmProvider.OPENAI, LlmProvider.fromId("openai"))
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, LlmProvider.fromId("openai-compatible"))
        assertEquals(LlmProvider.OLLAMA, LlmProvider.fromId("ollama"))
        assertEquals(LlmProvider.CUSTOM, LlmProvider.fromId("custom"))
    }

    @Test fun fromIdFallsBackToDefaultForUnknownId() {
        assertEquals(LlmProvider.DEFAULT, LlmProvider.fromId("anthropic"))
        assertEquals(LlmProvider.DEFAULT, LlmProvider.fromId(""))
        assertEquals(LlmProvider.DEFAULT, LlmProvider.fromId("???"))
    }

    @Test fun fromIdFallsBackToDefaultForNull() {
        assertEquals(LlmProvider.DEFAULT, LlmProvider.fromId(null))
    }

    @Test fun defaultIsOpenAi() {
        assertEquals(LlmProvider.OPENAI, LlmProvider.DEFAULT)
    }

    @Test fun everyProviderHasNonBlankDefaultUrlExceptCustom() {
        LlmProvider.entries
            .filter { it != LlmProvider.CUSTOM }
            .forEach {
                assertEquals(
                    expected = true,
                    actual = it.defaultBaseUrl.isNotBlank(),
                    message = "${it.id} has blank default URL",
                )
            }
        assertEquals(expected = "", actual = LlmProvider.CUSTOM.defaultBaseUrl)
    }
}
