package com.singularity.todo.feature.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the model-id → [ai.koog.prompt.llm.LLModel] mapping used by
 * [KoogAgentService]. Compares by `id` only — reading `OpenAIModels.Chat.*`
 * static constants in the JVM-test classpath triggers a `<clinit>` NPE
 * (Koog 1.1.1 + Robolectric), so we look up the model id via the production
 * path and compare against the literal expected id.
 */
class ModelResolverTest {

    @Test fun `resolves all known model ids to non-empty LLModel instances`() {
        val ids = listOf(
            "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-nano", "gpt-4.1-mini",
            "o1", "o3", "o3-mini", "o4-mini", "gpt-5", "gpt-5-mini",
        )
        for (id in ids) {
            val model = resolveModel(id)
            assertEquals(expected = id, actual = model.id, message = "id mismatch for $id")
            assertEquals(expected = "openai", actual = model.provider.id)
        }
    }

    @Test fun `unknown id falls back to GPT4oMini`() {
        assertEquals(expected = "gpt-4o-mini", actual = resolveModel("claude-3").id)
        assertEquals(expected = "gpt-4o-mini", actual = resolveModel("").id)
        assertEquals(expected = "gpt-4o-mini", actual = resolveModel("???").id)
    }
}