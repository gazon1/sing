package com.singularity.todo.core.di

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.OpenAILLMProvider
import ai.koog.utils.time.KoogClock

/**
 * JVM actual for [createKoogPromptExecutor].
 *
 * Creates a [MultiLLMPromptExecutor] wrapping an OpenAI client with OkHttp HTTP backend.
 * The API key is passed as empty string — it is resolved at runtime inside
 * [com.singularity.todo.feature.ai.KoogAgentService] via [SecureStoragePort].
 */
actual fun createKoogPromptExecutor(): PromptExecutor {
    val settings = OpenAIClientSettings() // no-arg uses all defaults
    val httpClientFactory = OkHttpKoogHttpClient.Factory()

    // 4-arg constructor: (apiKey, settings, httpClientFactory, clock)
    val openAIClient = OpenAILLMClient(
        apiKey = "", // resolved at runtime via KoogAgentService.secureStorage
        settings = settings,
        httpClientFactory = httpClientFactory,
        clock = KoogClock.System
    )
    return MultiLLMPromptExecutor(mapOf(OpenAILLMProvider to openAIClient))
}
