package com.singularity.todo.core.di

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import org.koin.dsl.module

/**
 * Android actual for [aiToolsModule].
 *
 * Shares the common bindings (use cases, tools, AI service, GenUI, ChatViewModel,
 * TasksViewModel, ProjectsViewModel) via [aiToolsCoreModule], then adds the
 * platform-specific bits: real [PromptExecutorPort] built by
 * [createKoogPromptExecutor], the default [LLModel], and the raw
 * [ai.koog.prompt.executor.model.PromptExecutor] for AI tools.
 */
actual fun aiToolsModule() = module {
    includes(aiToolsCoreModule())

    // Default LLM
    single<LLModel> { OpenAIModels.Chat.GPT4oMini }

    // Real Koog executor on Android
    single<PromptExecutorPort> {
        createKoogPromptExecutor(get<SecureStoragePort>(), get<SettingsRepository>())
    }

    // Raw Koog executor for AI tools
    single<ai.koog.prompt.executor.model.PromptExecutor> {
        (get<PromptExecutorPort>() as KoogPromptExecutorPort).executor
    }
}