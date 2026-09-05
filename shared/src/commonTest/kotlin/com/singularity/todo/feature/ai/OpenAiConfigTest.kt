package com.singularity.todo.feature.ai

import com.singularity.todo.core.security.FakeSecureStorage
import com.singularity.todo.test.fakes.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiConfigTest {

    @Test fun `resolve returns empty key when no key stored`() = runTest {
        val secure = FakeSecureStorage()
        val settings = FakeSettingsRepository()

        val cfg = OpenAiConfig.resolve(secure, settings)

        assertFalse(cfg.apiKey.isConfigured)
        assertEquals(ApiKey.EMPTY, cfg.apiKey)
    }

    @Test fun `resolve reads stored api key`() = runTest {
        val secure = FakeSecureStorage(mutableMapOf(OpenAiConfig.KEY_OPENAI to "sk-test"))
        val settings = FakeSettingsRepository()

        val cfg = OpenAiConfig.resolve(secure, settings)

        assertTrue(cfg.apiKey.isConfigured)
        assertEquals("sk-test", cfg.apiKey.value)
    }

    @Test fun `resolve applies provider default URL when settings has blank URL`() = runTest {
        val secure = FakeSecureStorage(mutableMapOf(OpenAiConfig.KEY_OPENAI to "k"))
        val settings = FakeSettingsRepository().apply {
            // Ollama chosen, blank base URL — should default to localhost.
            // We poke into the underlying StateFlow via the public setter.
        }

        // The FakeSettingsRepository initial values are the OpenAI defaults.
        // Override via setAiBaseUrl("") to assert the fallback.
        settings.setAiBaseUrl("")

        val cfg = OpenAiConfig.resolve(secure, settings)

        // Default provider in FakeSettingsRepository is OPENAI
        assertEquals(LlmProvider.OPENAI.defaultBaseUrl, cfg.baseUrl)
    }

    @Test fun `resolve preserves custom base URL over provider default`() = runTest {
        val secure = FakeSecureStorage(mutableMapOf(OpenAiConfig.KEY_OPENAI to "k"))
        val settings = FakeSettingsRepository().apply {
            setAiBaseUrl("https://my-proxy.example.com/v1")
        }

        val cfg = OpenAiConfig.resolve(secure, settings)

        assertEquals("https://my-proxy.example.com/v1", cfg.baseUrl)
    }

    @Test fun `resolve falls back to DEFAULT_MODEL when settings blank`() = runTest {
        val secure = FakeSecureStorage(mutableMapOf(OpenAiConfig.KEY_OPENAI to "k"))
        val settings = FakeSettingsRepository().apply {
            setAiModel("")
        }

        val cfg = OpenAiConfig.resolve(secure, settings)

        assertEquals(OpenAiConfig.DEFAULT_MODEL, cfg.defaultModelId)
    }

    @Test fun `resolve uses stored model when set`() = runTest {
        val secure = FakeSecureStorage(mutableMapOf(OpenAiConfig.KEY_OPENAI to "k"))
        val settings = FakeSettingsRepository().apply {
            setAiModel("gpt-4o")
        }

        val cfg = OpenAiConfig.resolve(secure, settings)

        assertEquals("gpt-4o", cfg.defaultModelId)
    }

    @Test fun `resolve maps unknown provider id to OPENAI`() = runTest {
        val secure = FakeSecureStorage(mutableMapOf(OpenAiConfig.KEY_OPENAI to "k"))
        val settings = FakeSettingsRepository().apply {
            setAiProvider("anthropic")
        }

        val cfg = OpenAiConfig.resolve(secure, settings)

        assertEquals(LlmProvider.OPENAI, cfg.provider)
    }

    @Test fun `resolve uses Ollama default URL when provider is Ollama and URL blank`() = runTest {
        val secure = FakeSecureStorage(mutableMapOf(OpenAiConfig.KEY_OPENAI to "k"))
        val settings = FakeSettingsRepository().apply {
            setAiProvider("ollama")
            setAiBaseUrl("")
        }

        val cfg = OpenAiConfig.resolve(secure, settings)

        assertEquals(LlmProvider.OLLAMA, cfg.provider)
        assertEquals("http://localhost:11434/v1", cfg.baseUrl)
    }

    // ─── resolveBaseUrl (pure) ─────────────────────────────────────────────────

    @Test fun `resolveBaseUrl returns provider default when stored is blank`() {
        assertEquals("https://api.openai.com/v1", OpenAiConfig.resolveBaseUrl("", LlmProvider.OPENAI))
        assertEquals("http://localhost:11434/v1", OpenAiConfig.resolveBaseUrl("", LlmProvider.OLLAMA))
    }

    @Test fun `resolveBaseUrl replaces stored URL when it matches another provider default`() {
        // User had Ollama, switched to OpenAI. URL is Ollama's default → replace.
        assertEquals(
            "https://api.openai.com/v1",
            OpenAiConfig.resolveBaseUrl("http://localhost:11434/v1", LlmProvider.OPENAI),
        )
    }

    @Test fun `resolveBaseUrl keeps stored URL when it is a custom endpoint`() {
        assertEquals(
            "https://my-proxy.example.com/v1",
            OpenAiConfig.resolveBaseUrl("https://my-proxy.example.com/v1", LlmProvider.OPENAI),
        )
    }

    @Test fun `resolve swaps URL when it matches provider's own default (no-op)`() {
        // URL == current provider's default → keep.
        assertEquals(
            "https://api.openai.com/v1",
            OpenAiConfig.resolveBaseUrl("https://api.openai.com/v1", LlmProvider.OPENAI),
        )
    }
}