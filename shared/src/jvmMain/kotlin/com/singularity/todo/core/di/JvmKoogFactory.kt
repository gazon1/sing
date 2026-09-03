package com.singularity.todo.core.di

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.OpenAILLMProvider
import ai.koog.utils.time.KoogClock

/**
 * JVM actual for [createKoogPromptExecutor].
 *
 * Creates a [JvmPromptExecutorPort] wrapping a [MultiLLMPromptExecutor]
 * with OkHttp HTTP backend. The API key is resolved at runtime
 * inside [com.singularity.todo.feature.ai.KoogAgentService] via [SecureStoragePort].
 */
actual fun createKoogPromptExecutor(): PromptExecutorPort {
    val settings = OpenAIClientSettings()
    val httpClientFactory = OkHttpKoogHttpClient.Factory()
    val openAIClient = OpenAILLMClient(
        apiKey = "", // resolved at runtime via KoogAgentService.secureStorage
        settings = settings,
        httpClientFactory = httpClientFactory,
        clock = KoogClock.System
    )
    val executor = MultiLLMPromptExecutor(mapOf(OpenAILLMProvider to openAIClient))
    return JvmPromptExecutorPort(executor)
}
