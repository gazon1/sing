package com.singularity.todo.core.di

import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.ai.OpenAiConfig

/**
 * Android actual for [createKoogPromptExecutor].
 *
 * Reads an [OpenAiConfig] from secure storage + settings, builds a Koog
 * [ai.koog.prompt.executor.model.PromptExecutor] backed by OkHttp, and
 * wraps it in [KoogPromptExecutorPort]. Suspend: the DI layer is
 * expected to bridge with `runBlocking` (single one-shot read at startup).
 */
actual suspend fun createKoogPromptExecutor(
    secureStorage: SecureStoragePort,
    settings: SettingsRepository,
): PromptExecutorPort {
    val cfg = OpenAiConfig.resolve(secureStorage, settings)
    val executor = buildExecutor(cfg)
    return KoogPromptExecutorPort(executor)
}

/**
 * Builds a [ai.koog.prompt.executor.llms.MultiLLMPromptExecutor] for an
 * OpenAI-compatible endpoint. Lives in androidMain so we can keep the
 * OkHttp HTTP backend on Android without leaking it into commonMain —
 * the JVM side has its own copy.
 */
internal fun buildExecutor(cfg: OpenAiConfig) = ai.koog.prompt.executor.llms.MultiLLMPromptExecutor(
    mapOf(
        ai.koog.prompt.llm.OpenAILLMProvider to ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
            apiKey = cfg.apiKey.value,
            settings = ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(baseUrl = cfg.baseUrl),
            httpClientFactory = ai.koog.http.client.okhttp.OkHttpKoogHttpClient.Factory(),
            clock = ai.koog.utils.time.KoogClock.System,
        ),
    ),
)